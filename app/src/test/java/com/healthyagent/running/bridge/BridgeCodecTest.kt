package com.healthyagent.running.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

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
            "/v1/fixtures/valid/command.settings-query.json",
            "/v1/fixtures/valid/command.settings-configure-deepseek.json",
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
    @Test fun `DeepSeek配置命令只接受空body且配置状态必须是布尔`() {
        val command = checkNotNull(javaClass.getResource("/v1/fixtures/valid/command.settings-query.json")).readText()
            .replace("settings.query", "settings.configureDeepseek")
        assertTrue(BridgeCodec.decode(command) is BridgeDecodeResult.Accepted)
        assertTrue(BridgeCodec.decode(command.replace("\"body\": {}", "\"body\": {\"apiKey\": \"test-only-value\"}")) is BridgeDecodeResult.Rejected)
        val raw = checkNotNull(javaClass.getResource("/v1/fixtures/valid/snapshot.json")).readText()
        val root = Json.parseToJsonElement(raw).jsonObject
        val payload = root.getValue("payload").jsonObject
        val settings = payload.getValue("settings").jsonObject
        fun modified(value: kotlinx.serialization.json.JsonElement?) = JsonObject(root + ("payload" to JsonObject(payload +
            ("settings" to JsonObject((settings - "deepseekApiKeyConfigured") + (value?.let { mapOf("deepseekApiKeyConfigured" to it) } ?: emptyMap())))))).toString()
        assertTrue(BridgeCodec.decode(modified(JsonPrimitive(true))) is BridgeDecodeResult.Accepted)
        assertTrue(BridgeCodec.decode(modified(null)) is BridgeDecodeResult.Rejected)
        assertTrue(BridgeCodec.decode(modified(JsonPrimitive("true"))) is BridgeDecodeResult.Rejected)
    }

    @Test fun `数据质量接受全部枚举且拒绝缺失未知枚举及额外字段`() {
        val raw = checkNotNull(javaClass.getResource("/v1/fixtures/valid/snapshot.json")).readText()
        val envelope = Json.parseToJsonElement(raw).jsonObject
        val payload = envelope.getValue("payload").jsonObject
        fun withQuality(quality: JsonObject?) = JsonObject(envelope + ("payload" to JsonObject(
            (payload - "dataQuality") + (quality?.let { mapOf("dataQuality" to it) } ?: emptyMap()),
        ))).toString()
        for (gps in listOf("waiting", "fresh", "stale")) {
            for (heartRate in listOf("not_available", "waiting", "fresh", "stale")) {
                assertTrue(BridgeCodec.decode(withQuality(JsonObject(mapOf(
                    "gps" to JsonPrimitive(gps), "heartRate" to JsonPrimitive(heartRate),
                )))) is BridgeDecodeResult.Accepted)
            }
        }
        val valid = mapOf("gps" to JsonPrimitive("fresh"), "heartRate" to JsonPrimitive("fresh"))
        listOf(null, JsonObject(valid - "gps"), JsonObject(valid + ("gps" to JsonPrimitive("lost"))),
            JsonObject(valid + ("heartRate" to JsonPrimitive("lost"))),
            JsonObject(valid + ("connected" to JsonPrimitive(true))),
        ).forEach { assertTrue(BridgeCodec.decode(withQuality(it)) is BridgeDecodeResult.Rejected) }
    }

    @Test
    fun `心率警戒更新接受空值和合法整数边界`() {
        for (threshold in listOf(JsonNull, JsonPrimitive(80), JsonPrimitive(240))) {
            for (interval in listOf(30, 300)) {
                assertTrue(BridgeCodec.decode(hrSettingsCommand(JsonObject(mapOf(
                    "hrAlertThresholdBpm" to threshold,
                    "hrAlertRepeatIntervalSec" to JsonPrimitive(interval),
                )))) is BridgeDecodeResult.Accepted)
            }
        }
    }

    @Test
    fun `心率警戒更新拒绝越界字符串小数和多余字段`() {
        val invalid = listOf(
            "hrAlertThresholdBpm" to JsonPrimitive(79),
            "hrAlertThresholdBpm" to JsonPrimitive(241),
            "hrAlertThresholdBpm" to JsonPrimitive("170"),
            "hrAlertThresholdBpm" to JsonPrimitive(170.5),
            "hrAlertRepeatIntervalSec" to JsonPrimitive(29),
            "hrAlertRepeatIntervalSec" to JsonPrimitive(301),
            "hrAlertRepeatIntervalSec" to JsonPrimitive(120.5),
            "hrAlertRepeatIntervalSec" to JsonNull,
            "unknownAlertSetting" to JsonPrimitive(120),
        )
        invalid.forEach { (key, value) ->
            assertTrue(BridgeCodec.decode(hrSettingsCommand(JsonObject(mapOf(key to value)))) is BridgeDecodeResult.Rejected)
        }
    }

    private fun hrSettingsCommand(body: JsonObject): String {
        val raw = checkNotNull(javaClass.getResource("/v1/fixtures/valid/command.settings-update.json")).readText()
        val envelope = Json.parseToJsonElement(raw).jsonObject
        val payload = JsonObject(envelope.getValue("payload").jsonObject + ("body" to body))
        return JsonObject(envelope + ("payload" to payload)).toString()
    }

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

    @Test
    fun `未知持久化与健康同步状态会被拒绝`() {
        val raw = checkNotNull(javaClass.getResource("/v1/fixtures/valid/snapshot.json")).readText()

        val invalidPersistence = raw.replace(
            "\"persistenceState\": \"idle\"",
            "\"persistenceState\": \"unknown\"",
        )
        val invalidHealth = raw.replace(
            "\"state\": \"idle\",\n      \"routeWritten\"",
            "\"state\": \"unknown\",\n      \"routeWritten\"",
        )

        assertTrue(BridgeCodec.decode(invalidPersistence) is BridgeDecodeResult.Rejected)
        assertTrue(BridgeCodec.decode(invalidHealth) is BridgeDecodeResult.Rejected)
    }

    @Test
    fun `没有心率时不能携带心率区间`() {
        val raw = checkNotNull(javaClass.getResource("/v1/fixtures/valid/snapshot.json")).readText()
        val invalid = raw.replace("\"heartRate\": 142", "\"heartRate\": null")

        assertTrue(BridgeCodec.decode(invalid) is BridgeDecodeResult.Rejected)
    }
}
