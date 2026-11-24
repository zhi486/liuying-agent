package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryContextState
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

/** 真实 Node 处理器与原生客户端互通；仅临时证书、回环及合成 SQLite。 */
class ConversationSummaryProcessTest {
    @Test fun 跨进程提交回执丢失重开只查询去重及撤回不恢复正文() = exercise(false)

    @Test fun 服务工厂鉴权精确计数及独立用量经跨进程恢复() = exercise(true)

    private fun exercise(factory: Boolean) = runBlocking {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "agent-server/tools/summary-loopback.ts").isFile }
        val directory = Files.createTempDirectory("liuying-summary-process-").toFile()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        File(directory, "certificate.pem").writeText(certificate.certificatePem())
        File(directory, "key.pem").writeText(certificate.privateKeyPkcs8Pem())
        val tls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val http = CloudTaskHttpClient.clientBuilder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
            .callTimeout(5, TimeUnit.SECONDS).build()
        val bearer = "lyd_" + "a".repeat(64)
        val client = ConversationSummaryHttpClient(CloudCredentialSource { CloudDeviceBearer(bearer) }, http)
        val id = UUID.randomUUID().toString()
        val hash = "a".repeat(64)
        val candidate = ConversationSummaryPolicy.Candidate(hash, listOf(ConversationSummaryPolicy.Turn(
            UUID.randomUUID().toString(), "合成问题", "合成回答", MemoryContextState())), emptySet())
        val snapshot = ConversationSummaryProtocol.Snapshot(id, hash,
            ConversationSummaryProtocol.State.ATTEMPTED, ConversationSummaryProtocol.Cost.UNCERTAIN)
        var calls = 0
        var counts = 0
        // 保留临时数据供核查；每个进程都在 finally 内等待退出。
        suspend fun session(dropResponse: Boolean = false, action: suspend (CloudTaskIdentity) -> Unit) {
            val process = ProcessBuilder("node", "--experimental-strip-types",
                File(root, "agent-server/tools/summary-loopback.ts").absolutePath,
                File(directory, "certificate.pem").absolutePath, File(directory, "key.pem").absolutePath,
                File(directory, "requests.sqlite").absolutePath, if (dropResponse) "drop-first-submit" else "normal",
                if (factory) "factory" else "legacy")
                .redirectError(File(directory, "node-" + UUID.randomUUID() + ".log")).start()
            val output = process.inputStream.bufferedReader()
            try {
                val ready = java.util.concurrent.CompletableFuture.supplyAsync { output.readLine() }
                    .get(15, TimeUnit.SECONDS)
                assertTrue("Node 未就绪：" + ready, ready?.startsWith("PORT ") == true)
                action(CloudTaskIdentity("https://127.0.0.1:" + ready!!.removePrefix("PORT "), "owner", "device", "generation"))
            } finally {
                runCatching { process.outputStream.write(1); process.outputStream.flush() }
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                }
                val lines = output.readLines()
                calls += lines.count { it == "MODEL" }
                counts += lines.count { it.startsWith("COUNT ") && it.removePrefix("COUNT ").toInt() > 0 }
                assertEquals("Node 测试宿主异常退出", 0, process.exitValue())
            }
        }
        session(dropResponse = true) { identity ->
            val submit = ConversationSummaryProtocol.Step(snapshot, ConversationSummaryProtocol.Action.SUBMIT)
            assertNotNull(runCatching { client.execute(identity, submit, candidate) }.exceptionOrNull())
        }
        assertEquals(1, calls)
        session { identity ->
            val query = ConversationSummaryProtocol.Step(snapshot, ConversationSummaryProtocol.Action.QUERY)
            val restored = client.execute(identity, query, null)
            assertEquals("跨进程合成摘要", restored.acceptedSummary(candidate))
            assertEquals(7L, restored.usage?.inputTokens)
            // 显式重复提交仅验证服务端去重，不是客户端失联恢复策略。
            val duplicate = ConversationSummaryProtocol.Step(snapshot, ConversationSummaryProtocol.Action.SUBMIT)
            assertEquals("跨进程合成摘要", client.execute(identity, duplicate, candidate).acceptedSummary(candidate))
            http.newCall(Request.Builder().url(identity.origin + "/v1/agent/summaries/" + id + "/withdraw")
                .tag(java.util.concurrent.atomic.AtomicBoolean::class.java, java.util.concurrent.atomic.AtomicBoolean(false))
                .header("Authorization", "Bearer " + bearer).post(ByteArray(0).toRequestBody()).build()).execute().use {
                assertEquals(200, it.code)
            }
            val withdrawn = client.execute(identity, query, null)
            assertEquals("withdrawn", withdrawn.state)
            assertTrue(withdrawn.remoteFinished)
            assertNull(withdrawn.acceptedSummary(candidate))
            assertEquals(7L, withdrawn.usage?.inputTokens)
        }
        assertEquals(1, calls)
        assertEquals(if (factory) 1 else 0, counts)
    }
}
