package com.healthyagent.running.data.sensor.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class KeepB5B3ProtocolTest {
    @Test
    fun `官方动态屏幕样本包络复现且不含地址或位置`() {
        // 2026-09-09 本地抓包记录 3660：手机 0.15 公里、13分36秒、12千卡。
        assertArrayEquals(
            hex("e2 00 00 00 00 0e 06 08 01 28 b0 60 32 05 08 02 10 99 01 " +
                "32 05 08 03 10 b0 06 32 04 08 01 10 0c 38 9d 01 db d7"),
            KeepB5B3Protocol.encodeRequest(0xe2, 0x0e, 0x06,
                hex("08 01 28 b0 60 32 05 08 02 10 99 01 32 05 08 03 10 b0 06 " +
                    "32 04 08 01 10 0c 38 9d 01")),
        )
    }

    @Test
    fun `动态距离配速保留官方字段但不伪造千卡`() {
        // 由上述官方样本仅清除千卡非零值；CRC 由独立离线解析器计算。
        assertArrayEquals(
            hex("e2 00 00 00 00 0e 06 08 01 28 b0 60 32 05 08 02 10 99 01 " +
                "32 05 08 03 10 b0 06 32 02 08 01 38 9d 01 db f2"),
            KeepB5B3Protocol.buildWorkoutStatusRequest(0xe2, 157, KeepB5DisplayMetrics(153, 816)),
        )
    }

    @Test
    fun `无效配速按手环横杠样本编码为缺省零`() {
        // 记录 3890 的 B5 实拍：0.18 公里、配速横杠、3分23秒；千卡仍保留缺省零。
        assertArrayEquals(
            hex("1c 01 00 00 00 0e 06 08 01 28 b0 60 32 05 08 02 10 ba 01 " +
                "32 02 08 03 32 02 08 01 38 cb 01 81 f0"),
            KeepB5B3Protocol.buildWorkoutStatusRequest(0x11c, 203, KeepB5DisplayMetrics(186, 0)),
        )
    }

    @Test
    fun `编码抓包中的开始运动请求`() {
        val protobuf = hex(
            "08 03 10 01 20 1f 2a 11 08 01 28 b0 60 32 02 08 02 " +
                "32 02 08 03 32 02 08 01 30 01 38 f0 e1 e9 d4 06 40 01 48 03",
        )

        assertArrayEquals(
            hex(
                "60 00 00 00 00 0e 05 08 03 10 01 20 1f 2a 11 08 01 28 b0 60 " +
                    "32 02 08 02 32 02 08 03 32 02 08 01 30 01 38 f0 e1 e9 d4 06 " +
                    "40 01 48 03 2d 7f",
            ),
            KeepB5B3Protocol.encodeRequest(
                requestId = 0x60,
                group = 0x0e,
                command = 0x05,
                protobuf = protobuf,
            ),
        )
    }

    @Test
    fun `编码抓包中的训练心跳请求`() {
        assertArrayEquals(
            hex("63 00 00 00 00 0e 10 a6 5a"),
            KeepB5B3Protocol.encodeRequest(
                requestId = 0x63,
                group = 0x0e,
                command = 0x10,
                protobuf = byteArrayOf(),
            ),
        )
    }

    @Test
    fun `开始运动构建器复现抓包模板并动态编码时间`() {
        assertArrayEquals(
            hex(
                "60 00 00 00 00 0e 05 08 03 10 01 20 1f 2a 11 08 01 28 b0 60 " +
                    "32 02 08 02 32 02 08 03 32 02 08 01 30 01 38 f0 e1 e9 d4 06 " +
                    "40 01 48 03 2d 7f",
            ),
            KeepB5B3Protocol.buildStartWorkoutRequest(
                requestId = 0x60,
                epochSeconds = 1_788_506_352L,
            ),
        )
    }

    @Test
    fun `训练心跳构建器复现抓包请求`() {
        assertArrayEquals(
            hex("63 00 00 00 00 0e 10 a6 5a"),
            KeepB5B3Protocol.buildTrainingHeartbeatRequest(requestId = 0x63),
        )
    }

    @Test
    fun `运动状态构建器复现抓包中的一秒状态请求`() {
        assertArrayEquals(
            hex(
                "61 00 00 00 00 0e 06 08 01 28 b0 60 32 02 08 02 32 02 08 03 " +
                    "32 02 08 01 38 01 9a fe",
            ),
            KeepB5B3Protocol.buildWorkoutStatusRequest(
                requestId = 0x61,
                elapsedSeconds = 1L,
            ),
        )
    }

    @Test
    fun `运动状态拒绝负数时长`() {
        assertThrows(IllegalArgumentException::class.java) {
            KeepB5B3Protocol.buildWorkoutStatusRequest(
                requestId = 0x61,
                elapsedSeconds = -1L,
            )
        }
    }

    @Test
    fun `解析抓包中的连续心率推送`() {
        assertEquals(79, KeepB5B3Protocol.parseWorkoutData(hex("ea 2a 02 00 00 0e 08 08 4f da d0"))?.heartRate)
        assertEquals(78, KeepB5B3Protocol.parseWorkoutData(hex("eb 2a 02 00 00 0e 08 08 4e fb c0"))?.heartRate)
    }

    @Test
    fun acceptsValidWorkoutDataWithoutHeartRate() {
        val parsed = KeepB5B3Protocol.parseWorkoutData(hex("e7 2a 02 00 00 0e 08 84 e6"))

        assertNotNull(parsed)
        assertNull(parsed?.heartRate)
    }

    @Test
    fun returnsOptionalHeartRateFromValidWorkoutData() {
        val parsed = KeepB5B3Protocol.parseWorkoutData(hex("ea 2a 02 00 00 0e 08 08 4f da d0"))

        assertEquals(79, parsed?.heartRate)
    }

    @Test
    fun keepsStructurallyValidOutOfRangeHeartRateAsActivityWithoutHeartRate() {
        listOf(
            "ea 2a 02 00 00 0e 08 08 00 f1 69",
            "ea 2a 02 00 00 0e 08 08 ad 02 8e ff",
        ).forEach { frame ->
            val parsed = KeepB5B3Protocol.parseWorkoutData(hex(frame))
            assertNotNull(parsed)
            assertNull(parsed?.heartRate)
        }
    }

    @Test
    fun `心率字段可位于其他 protobuf 字段之后`() {
        assertEquals(
            79,
            KeepB5B3Protocol.parseWorkoutData(hex("ea 2a 02 00 00 0e 08 10 01 08 4f 91 57"))?.heartRate,
        )
    }

    @Test
    fun `合法空推送不产生心率`() {
        val parsed = KeepB5B3Protocol.parseWorkoutData(hex("e7 2a 02 00 00 0e 08 84 e6"))

        assertNotNull(parsed)
        assertNull(parsed?.heartRate)
    }

    @Test
    fun `心率后存在截断的 protobuf 字段时拒绝整包`() {
        assertNull(
            KeepB5B3Protocol.parseWorkoutData(
                hex("ec 2a 02 00 00 0e 08 08 4f 12 02 01 93 75"),
            ),
        )
    }

    @Test
    fun `field 1 非 VARINT wire type 的合法包络被拒绝`() {
        assertNull(
            KeepB5B3Protocol.parseWorkoutData(
                hex("ea 2a 02 00 00 0e 08 0a 01 4f 94 50"),
            ),
        )
    }

    @Test
    fun `超过 protobuf 最大 field number 的合法 CRC 包被拒绝`() {
        assertNull(
            KeepB5B3Protocol.parseWorkoutData(
                hex("ea 2a 02 00 00 0e 08 80 80 80 80 10 00 51 cd"),
            ),
        )
    }

    @Test
    fun `错误 CRC 命令和截断包被拒绝`() {
        assertNull(KeepB5B3Protocol.parseWorkoutData(hex("ea 2a 02 00 00 0e 08 08 4f da d1")))
        assertNull(KeepB5B3Protocol.parseWorkoutData(hex("ea 2a 02 00 00 0e 09 08 4f ea e7")))
        assertNull(KeepB5B3Protocol.parseWorkoutData(hex("ea 2a 02 00 00 0e 08 08 4f da")))
    }

    @Test
    fun `仅接受单分片运动数据推送包络`() {
        assertNull(KeepB5B3Protocol.parseWorkoutData(hex("ea 2a 01 00 00 0e 08 08 4f 58 08")))
        assertNull(KeepB5B3Protocol.parseWorkoutData(hex("ea 2a 02 01 00 0e 08 08 4f da d0")))
        assertNull(KeepB5B3Protocol.parseWorkoutData(hex("ea 2a 02 00 01 0e 08 08 4f da d0")))
        assertNull(KeepB5B3Protocol.parseWorkoutData(hex("ea 2a 02 00 00 0d 08 08 4f 06 4b")))
    }

    private fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
