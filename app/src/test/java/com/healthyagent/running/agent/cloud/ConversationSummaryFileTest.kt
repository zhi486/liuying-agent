package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryContextState
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.io.File
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** 仅测试专用文件原型，不接 App；临时目录保留供核查。 */
class ConversationSummaryFileTest {
    private val policy = ConversationSummaryProtocol
    private val candidate = ConversationSummaryPolicy.plan(
        ConversationSummaryPolicy.Scope(CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential"),
            UUID.randomUUID().toString(), "grant", null),
        (1..5).map { ConversationSummaryPolicy.Turn(UUID.randomUUID().toString(), "合成问题$it", "合成回答$it", MemoryContextState()) },
        emptySet()).candidate!!

    private class Store(private val directory: Path) {
        val file: Path = directory.resolve("snapshot.json")
        fun read() = ConversationSummaryProtocol.decode(String(Files.readAllBytes(file), Charsets.UTF_8))

        fun save(snapshot: ConversationSummaryProtocol.Snapshot, beforeReplace: () -> Unit = {}) {
            val pending = Files.createTempFile(directory, "pending-", ".json")
            FileChannel.open(pending, WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(ConversationSummaryProtocol.encode(snapshot).toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            beforeReplace()
            Files.move(pending, file, ATOMIC_MOVE, REPLACE_EXISTING)
        }

        fun claim(expected: ConversationSummaryProtocol.Snapshot, current: ConversationSummaryPolicy.Candidate?,
            beforeReplace: () -> Unit = {}): ConversationSummaryProtocol.Step? =
            update(expected) { stored ->
                val step = ConversationSummaryProtocol.next(stored, current)
                save(step.snapshot, beforeReplace)
                step
            }

        fun receive(expected: ConversationSummaryProtocol.Snapshot, current: ConversationSummaryPolicy.Candidate?,
            result: ConversationSummaryProtocol.Result, beforeReplace: () -> Unit = {}): ConversationSummaryProtocol.Snapshot? =
            update(expected) { stored ->
                val updated = ConversationSummaryProtocol.receive(stored, expected.requestId, expected.fingerprint, current, result)
                save(updated, beforeReplace)
                updated
            }

        private fun <T> update(expected: ConversationSummaryProtocol.Snapshot,
            change: (ConversationSummaryProtocol.Snapshot) -> T): T? {
            FileChannel.open(directory.resolve("claim.lock"), CREATE, WRITE).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: return null
                lock.use {
                    val stored = read()
                    if (stored != expected) return null
                    return change(stored)
                }
            }
        }
    }

    private fun setup(): Pair<Path, ConversationSummaryProtocol.Snapshot> {
        val directory = Files.createTempDirectory("liuying-summary-synthetic-")
        val snapshot = policy.prepare(UUID.randomUUID().toString(), candidate)
        Store(directory).save(snapshot)
        return directory to snapshot
    }

    @Test fun 两个文件调用方争用旧快照最多一个领取提交() {
        val (directory, initial) = setup()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { executor.submit(Callable {
                start.await()
                Store(directory).claim(initial, candidate)?.action
            }) }
            start.countDown()
            val actions = jobs.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, actions.count { it == ConversationSummaryProtocol.Action.SUBMIT })
            val restored = Store(directory).read()
            assertEquals(initial.requestId, restored.requestId)
            assertEquals(ConversationSummaryProtocol.Action.QUERY, Store(directory).claim(restored, candidate)!!.action)
            assertNull(Store(directory).claim(initial, candidate))
        } finally { executor.shutdownNow() }
    }

    @Test fun 替换前中断保留旧文件且不返回提交许可() {
        val (directory, initial) = setup()
        try {
            Store(directory).claim(initial, candidate) { throw IllegalStateException("合成替换前中断") }
            fail("应中断保存")
        } catch (_: IllegalStateException) { }
        assertEquals(initial, Store(directory).read())
    }

    @Test fun 领取落盘后调用方中断重开只能查询同一请求() {
        val (directory, initial) = setup()
        assertEquals(ConversationSummaryProtocol.Action.SUBMIT, Store(directory).claim(initial, candidate)!!.action)
        val restored = Store(directory).read()
        val recovered = Store(directory).claim(restored, candidate)!!
        assertEquals(ConversationSummaryProtocol.Action.QUERY, recovered.action)
        assertEquals(initial.requestId, recovered.snapshot.requestId)
        assertEquals(ConversationSummaryProtocol.Cost.UNCERTAIN, recovered.snapshot.cost)
    }

    @Test fun 损坏文件拒绝领取且不替换成新准备态() {
        val (directory, initial) = setup()
        Files.write(Store(directory).file, "{".toByteArray(Charsets.UTF_8), TRUNCATE_EXISTING)
        try {
            Store(directory).claim(initial, candidate)
            fail("损坏快照应拒绝")
        } catch (_: IllegalArgumentException) { }
        assertEquals("{", String(Files.readAllBytes(Store(directory).file), Charsets.UTF_8))
    }

    @Test fun 独立进程持锁时不能领取且强制退出后锁释放() {
        val (directory, initial) = setup()
        val java = File(System.getProperty("java.home"), "bin/java.exe").absolutePath
        val classes = File(checkNotNull(SummaryLockProcess::class.java.protectionDomain).codeSource.location.toURI()).absolutePath
        val child = ProcessBuilder(java, "-cp", classes, SummaryLockProcess::class.java.name,
            directory.resolve("claim.lock").toString()).redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val ready = reader.submit(Callable { child.inputStream.bufferedReader().readLine() })
            assertEquals("LOCKED", ready.get(15, TimeUnit.SECONDS))
            assertNull(Store(directory).claim(initial, candidate))
            assertEquals(initial, Store(directory).read())
            child.destroyForcibly()
            assertTrue("测试子进程必须退出", child.waitFor(10, TimeUnit.SECONDS))
            var claimed: ConversationSummaryProtocol.Step? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (claimed == null && System.nanoTime() < deadline) {
                claimed = Store(directory).claim(initial, candidate)
                if (claimed == null) Thread.sleep(20)
            }
            assertEquals(ConversationSummaryProtocol.Action.SUBMIT, claimed?.action)
            val restored = Store(directory).read()
            assertEquals(ConversationSummaryProtocol.Action.QUERY, Store(directory).claim(restored, candidate)!!.action)
        } finally {
            child.destroyForcibly()
            assertTrue("测试子进程收尾必须退出", child.waitFor(10, TimeUnit.SECONDS))
            reader.shutdownNow()
        }
    }

    @Test fun 来源失效落盘后重开及来源恢复均不能重新领取() {
        val (directory, initial) = setup()
        val rejected = Store(directory).claim(initial, null)!!
        assertNull(rejected.action)
        assertEquals(ConversationSummaryProtocol.State.INVALID, rejected.snapshot.state)
        val restored = Store(directory).read()
        assertEquals(rejected.snapshot, restored)
        assertNull(Store(directory).claim(restored, candidate)!!.action)
        assertNull(Store(directory).claim(initial, candidate))
        assertEquals(initial.requestId, Store(directory).read().requestId)
    }

    @Test fun 缺少快照时拒绝领取且不自动创建文件() {
        val directory = Files.createTempDirectory("liuying-summary-missing-")
        val initial = policy.prepare(UUID.randomUUID().toString(), candidate)
        try {
            Store(directory).claim(initial, candidate)
            fail("缺少快照应拒绝")
        } catch (_: java.nio.file.NoSuchFileException) { }
        assertFalse(Files.exists(Store(directory).file))
    }

    @Test fun 失效先落盘后旧结果不能覆盖且重新读取只能补记用量() {
        val (directory, initial) = setup()
        val attempted = Store(directory).claim(initial, candidate)!!.snapshot
        Store(directory).claim(attempted, null)
        val result = ConversationSummaryProtocol.Result.Completed("迟到合成摘要", ConversationSummaryProtocol.Usage(40, 5))
        assertNull(Store(directory).receive(attempted, candidate, result))
        val invalid = Store(directory).read()
        assertEquals(ConversationSummaryProtocol.State.INVALID, invalid.state)
        val reconciled = Store(directory).receive(invalid, null, result)!!
        assertNull(reconciled.summary)
        assertEquals(result.usage, reconciled.usage)
        assertEquals(reconciled, Store(directory).read())
    }

    @Test fun 并发结果仅一份接纳且旧快照不能覆盖已完成正文() {
        val (directory, initial) = setup()
        val attempted = Store(directory).claim(initial, candidate)!!.snapshot
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { index -> executor.submit(Callable {
                start.await()
                Store(directory).receive(attempted, candidate,
                    ConversationSummaryProtocol.Result.Completed("合成摘要$index", ConversationSummaryProtocol.Usage(10, index.toLong())))
            }) }
            start.countDown()
            val accepted = jobs.mapNotNull { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, accepted.size)
            assertEquals(accepted.single(), Store(directory).read())
            assertNull(Store(directory).receive(attempted, candidate, ConversationSummaryProtocol.Result.Unknown))
            assertEquals(accepted.single(), Store(directory).read())
        } finally { executor.shutdownNow() }
    }

    @Test fun 结果替换前中断仍保留已尝试状态且重开只查询() {
        val (directory, initial) = setup()
        val attempted = Store(directory).claim(initial, candidate)!!.snapshot
        try {
            Store(directory).receive(attempted, candidate,
                ConversationSummaryProtocol.Result.Completed("未落盘摘要", ConversationSummaryProtocol.Usage(10, 2))) {
                throw IllegalStateException("合成结果保存中断")
            }
            fail("应中断结果保存")
        } catch (_: IllegalStateException) { }
        assertEquals(attempted, Store(directory).read())
        assertEquals(ConversationSummaryProtocol.Action.QUERY, Store(directory).claim(attempted, candidate)!!.action)
    }
}
