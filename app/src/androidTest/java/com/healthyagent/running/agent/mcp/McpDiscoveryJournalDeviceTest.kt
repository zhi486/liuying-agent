package com.healthyagent.running.agent.mcp

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Process
import com.healthyagent.running.agent.cloud.CloudChatConsent
import com.healthyagent.running.agent.cloud.CloudTaskFailure
import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 仅写独立 noBackup 合成目录，不读取用户配置、不联网、不播放。 */
@RunWith(AndroidJUnit4::class)
class McpDiscoveryJournalDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.noBackupFilesDir, "synthetic-mcp-journal-${UUID.randomUUID()}")
    private val identity = CloudTaskIdentity("https://agent.example.com", "synthetic", "device", "credential")
    private val id = UUID.randomUUID().toString()
    private val scope = McpPhoneScope(identity, id, id, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), McpEndpoint.parse("https://tools.example.com/mcp"))
    private val running = McpDiscoveryView(1000, 11000, "running", null, null)

    @Test fun 真实AtomicFile重开后仅GET恢复原操作且不读取插件配置() = runBlocking {
        val journal = McpDiscoveryJournal.create(directory); journal.begin(scope); journal.observe(scope, running)
        val config = McpPrivateConfiguration({ error("恢复不得读取插件配置") }, { _, _ -> error("不得保存秘密") }, { error("不得访问密钥") })
        var reads = 0
        val api = object : McpDiscoveryApi {
            override suspend fun startDiscovery(scope: McpPhoneScope): McpDiscoveryView = error("不得重启")
            override suspend fun takeDiscovery(scope: McpPhoneScope): McpPhoneRequest? = error("不得领取")
            override suspend fun replyDiscovery(scope: McpPhoneScope, packet: McpPhoneRequest, reply: kotlinx.serialization.json.JsonObject): Unit = error("不得回传")
            override suspend fun discovery(scope: McpPhoneScope): McpDiscoveryView {
                reads++; assertEquals(id, scope.requestId); assertEquals(scope.requestId, scope.taskId)
                return running.copy(status = "unconfirmed", reason = "interrupted")
            }
        }
        val service = McpDiscoveryService({ identity }, config, apiFactory = { api }, journal = McpDiscoveryJournal.create(directory),
            exchangeFactory = { _, _ -> error("不得创建插件交换器") })
        assertEquals("interrupted", service.recover(id).reason); assertEquals(1, reads)
        assertEquals("unconfirmed", McpDiscoveryJournal.create(directory).get(identity, id).view!!.status)
        assertTrue(directory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
    }

    @Test fun 真实AtomicFile未提交写入不覆盖已保存未知标识() {
        val journal = McpDiscoveryJournal.create(directory); journal.begin(scope)
        val file = File(directory, CloudChatConsent.context(identity) + ".json")
        val atomic = AtomicFile(file); val pending = atomic.startWrite()
        try {
            pending.write("partial-private-synthetic-write".toByteArray())
            val original = McpDiscoveryJournal.create(directory).get(identity, id)
            assertEquals(id, original.scope.requestId); assertNull(original.view)
        } finally { atomic.failWrite(pending) }
        assertNull(McpDiscoveryJournal.create(directory).get(identity, id).view)
    }

    @Test fun 真实文件坏UTF8失败关闭不覆盖也不当作空历史() {
        val journal = McpDiscoveryJournal.create(directory); journal.begin(scope)
        val file = File(directory, CloudChatConsent.context(identity) + ".json")
        val bad = byteArrayOf(0xc0.toByte(), 0x80.toByte()); file.writeBytes(bad)
        val failure = assertThrows(CloudTaskFailure::class.java) { McpDiscoveryJournal.create(directory).list(identity) }
        assertEquals("storage_unavailable", failure.code); assertArrayEquals(bad, file.readBytes())
    }

    @Test fun 隔离模拟器两阶段进程重启保留未知标识且只查原操作() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("mcpPhase")
        assumeTrue("需要隔离模拟器两阶段驱动", phase in setOf("seed", "recover"))
        val caseId = UUID.fromString(requireNotNull(args.getString("mcpCaseId"))).toString()
        val root = File(context.noBackupFilesDir, "synthetic-mcp-process-$caseId")
        val proof = File(root, "seed-pid.txt")
        val binding = McpPhoneScope(identity, caseId, caseId, "33333333-3333-4333-8333-333333333333",
            "44444444-4444-4444-8444-444444444444", "55555555-5555-4555-8555-555555555555", scope.endpoint)
        val journal = McpDiscoveryJournal.create(root)
        if (phase == "seed") {
            assertFalse(root.exists()); journal.begin(binding)
            proof.writeText(Process.myPid().toString(), Charsets.UTF_8)
            assertNull(journal.get(identity, caseId).view)
        } else {
            assertTrue(proof.exists()); assertNotEquals(proof.readText(Charsets.UTF_8).toInt(), Process.myPid())
            assertNull(journal.get(identity, caseId).view)
            var reads = 0
            val config = McpPrivateConfiguration({ error("重启恢复不得读取插件配置") }, { _, _ -> error("不得写配置") }, { error("不得访问密钥") })
            val api = object : McpDiscoveryApi {
                override suspend fun startDiscovery(scope: McpPhoneScope): McpDiscoveryView = error("不得重启扫描")
                override suspend fun takeDiscovery(scope: McpPhoneScope): McpPhoneRequest? = error("不得补发")
                override suspend fun replyDiscovery(scope: McpPhoneScope, packet: McpPhoneRequest, reply: kotlinx.serialization.json.JsonObject): Unit = error("不得回传")
                override suspend fun discovery(scope: McpPhoneScope): McpDiscoveryView {
                    reads++; assertEquals(caseId, scope.requestId); assertEquals(binding.generation, scope.generation)
                    return running.copy(status = "unconfirmed", reason = "interrupted")
                }
            }
            val service = McpDiscoveryService({ identity }, config, apiFactory = { api }, journal = journal,
                exchangeFactory = { _, _ -> error("重启不得创建插件交换器") })
            assertEquals("interrupted", service.recover(caseId).reason); assertEquals(1, reads)
            assertEquals("unconfirmed", McpDiscoveryJournal.create(root).get(identity, caseId).view!!.status)
        }
    }
}
