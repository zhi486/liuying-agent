package com.healthyagent.running.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BridgeCodecValidFixtureTest(private val resource: String) {

    @Test
    fun `合法合同样本可解码`() {
        val raw = checkNotNull(javaClass.getResource(resource)).readText()

        assertTrue(BridgeCodec.decode(raw) is BridgeDecodeResult.Accepted)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures() = listOf(
            "/v1/fixtures/valid/hello.json",
            "/v1/fixtures/valid/ready.json",
            "/v1/fixtures/valid/snapshot.json",
            "/v1/fixtures/valid/command.run-start.json",
            "/v1/fixtures/valid/command.history-query.json",
            "/v1/fixtures/valid/command.settings-update.json",
            "/v1/fixtures/valid/result.accepted.json",
            "/v1/fixtures/valid/result.error.json",
            "/v1/fixtures/valid/event.permission-result.json",
            "/v1/fixtures/valid/event.notice.json",
        ).map { arrayOf(it) }
    }
}

@RunWith(Parameterized::class)
class BridgeCodecInvalidFixtureTest(private val resource: String) {

    @Test
    fun `非法合同样本被拒绝`() {
        val raw = checkNotNull(javaClass.getResource(resource)).readText()

        assertTrue(BridgeCodec.decode(raw) is BridgeDecodeResult.Rejected)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures() = listOf(
            "/v1/fixtures/invalid/missing-contract-version.json",
            "/v1/fixtures/invalid/unknown-envelope-type.json",
            "/v1/fixtures/invalid/unsupported-version.json",
            "/v1/fixtures/invalid/invalid-command-payload.json",
            "/v1/fixtures/invalid/noncanonical-uuid.json",
            "/v1/fixtures/invalid/invalid-uuid-variant.json",
            "/v1/fixtures/invalid/unknown-nested-field.json",
        ).map { arrayOf(it) }
    }
}

class BridgeCodecTest {

    @Test
    fun `UTF-8 消息大小边界是 262144 字节`() {
        val raw = checkNotNull(javaClass.getResource("/v1/fixtures/valid/hello.json")).readText()
        val padding = " ".repeat(262_144 - raw.toByteArray(Charsets.UTF_8).size)

        assertTrue(BridgeCodec.decode(padding + raw) is BridgeDecodeResult.Accepted)
        assertEquals(
            BridgeDecodeResult.Rejected(BridgeErrorCode.INVALID_MESSAGE),
            BridgeCodec.decode(" $padding$raw"),
        )
    }

    @Test
    fun `编码后可由同一 codec 解码`() {
        val raw = checkNotNull(javaClass.getResource("/v1/fixtures/valid/snapshot.json")).readText()
        val accepted = BridgeCodec.decode(raw) as BridgeDecodeResult.Accepted

        val roundTrip = BridgeCodec.decode(BridgeCodec.encode(accepted.envelope))

        assertTrue(roundTrip is BridgeDecodeResult.Accepted)
    }
}
