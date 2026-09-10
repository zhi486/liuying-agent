package com.healthyagent.running.data.sensor.ble

internal data class ParsedWorkoutData(val heartRate: Int?)

/** Keep B5 的 B3 单分片运动协议。 */
internal object KeepB5B3Protocol {
    private const val HEADER_SIZE = 7
    private const val CRC_SIZE = 2
    private const val PACKET_TYPE_REQUEST = 0
    private const val PACKET_TYPE_PUSH = 2
    private const val PACKET_TYPE_MASK = 0x07
    private const val MAX_PROTOBUF_FIELD_NUMBER = 536_870_911L
    private const val WORKOUT_SID = 0x0e
    private const val START_WORKOUT_CID = 0x05
    private const val WORKOUT_STATUS_CID = 0x06
    private const val WORKOUT_DATA_CID = 0x08
    private const val TRAINING_HEARTBEAT_CID = 0x10

    private val startWorkoutPrefix = byteArrayOf(
        0x08,
        0x03,
        0x10,
        0x01,
        0x20,
        0x1f,
        0x2a,
        0x11,
        0x08,
        0x01,
        0x28,
        0xb0.toByte(),
        0x60,
        0x32,
        0x02,
        0x08,
        0x02,
        0x32,
        0x02,
        0x08,
        0x03,
        0x32,
        0x02,
        0x08,
        0x01,
        0x30,
        0x01,
        0x38,
    )
    private val startWorkoutSuffix = byteArrayOf(0x40, 0x01, 0x48, 0x03)
    private val workoutStatusPrefix = byteArrayOf(
        0x08,
        0x01,
        0x28,
        0xb0.toByte(),
        0x60,
    )

    /**
     * 编码 B3 单分片请求。
     *
     * 物理头为 `[requestIdLE16][control][lastFragmentIndex][fragmentIndex][sid][cid]`；
     * control 的 bits 0..2 是 PacketType，bit 3 是 flag，bits 4..7 是状态/保留位。
     */
    fun encodeRequest(
        requestId: Int,
        group: Int,
        command: Int,
        protobuf: ByteArray,
    ): ByteArray {
        require(requestId in 0..0xffff)
        require(group in 0..0xff)
        require(command in 0..0xff)
        require(protobuf.size <= 0xffff)

        val frame = ByteArray(HEADER_SIZE + protobuf.size + CRC_SIZE)
        frame[0] = requestId.toByte()
        frame[1] = (requestId ushr 8).toByte()
        frame[2] = PACKET_TYPE_REQUEST.toByte()
        frame[3] = 0
        frame[4] = 0
        frame[5] = group.toByte()
        frame[6] = command.toByte()
        protobuf.copyInto(frame, destinationOffset = HEADER_SIZE)

        val crc = packetCrc(
            requestId = requestId,
            control = PACKET_TYPE_REQUEST,
            sid = group,
            cid = command,
            protobuf = protobuf,
        )
        frame[frame.lastIndex - 1] = crc.toByte()
        frame[frame.lastIndex] = (crc ushr 8).toByte()
        return frame
    }

    /** 按真机抓包模板构建开始运动请求，仅动态替换 field 7 的 Unix 秒时间。 */
    fun buildStartWorkoutRequest(requestId: Int, epochSeconds: Long): ByteArray {
        require(epochSeconds >= 0L)
        val protobuf = startWorkoutPrefix + encodeVarint(epochSeconds) + startWorkoutSuffix
        return encodeRequest(
            requestId = requestId,
            group = WORKOUT_SID,
            command = START_WORKOUT_CID,
            protobuf = protobuf,
        )
    }

    /** 构建训练态保活请求。 */
    fun buildTrainingHeartbeatRequest(requestId: Int): ByteArray = encodeRequest(
        requestId = requestId,
        group = WORKOUT_SID,
        command = TRAINING_HEARTBEAT_CID,
        protobuf = byteArrayOf(),
    )

    /** 按官方动态抓包编码时长、整数米和秒/公里；千卡保留原缺省值，不生成估算。 */
    fun buildWorkoutStatusRequest(
        requestId: Int,
        elapsedSeconds: Long,
        displayMetrics: KeepB5DisplayMetrics = KeepB5DisplayMetrics(),
    ): ByteArray {
        require(elapsedSeconds >= 0L)
        return encodeRequest(
            requestId = requestId,
            group = WORKOUT_SID,
            command = WORKOUT_STATUS_CID,
            protobuf = workoutStatusPrefix +
                encodeDisplayData(2, displayMetrics.distanceMeters) +
                encodeDisplayData(3, displayMetrics.paceSecPerKm) +
                encodeDisplayData(1, 0) + byteArrayOf(0x38) + encodeVarint(elapsedSeconds),
        )
    }

    private fun encodeDisplayData(type: Int, value: Int): ByteArray {
        val data = byteArrayOf(0x08, type.toByte()) +
            if (value == 0) byteArrayOf() else byteArrayOf(0x10) + encodeVarint(value.toLong())
        return byteArrayOf(0x32, data.size.toByte()) + data
    }

    /** 解析单分片 WorkoutData push 的合法结构与可用心率。 */
    fun parseWorkoutData(frame: ByteArray): ParsedWorkoutData? {
        if (frame.size < HEADER_SIZE + CRC_SIZE) return null

        val control = frame[2].unsigned()
        if (control and PACKET_TYPE_MASK != PACKET_TYPE_PUSH) return null
        if (frame[3].unsigned() != 0 || frame[4].unsigned() != 0) return null

        val sid = frame[5].unsigned()
        val cid = frame[6].unsigned()
        if (sid != WORKOUT_SID || cid != WORKOUT_DATA_CID) return null

        val protobuf = frame.copyOfRange(HEADER_SIZE, frame.size - CRC_SIZE)
        val expectedCrc = packetCrc(
            requestId = 0,
            control = control,
            sid = sid,
            cid = cid,
            protobuf = protobuf,
        )
        val receivedCrc = frame[frame.lastIndex - 1].unsigned() or
            (frame[frame.lastIndex].unsigned() shl 8)
        if (receivedCrc != expectedCrc) return null

        return parseWorkoutFields(protobuf)
    }

    private fun parseWorkoutFields(protobuf: ByteArray): ParsedWorkoutData? {
        var offset = 0
        var heartRate: Int? = null
        while (offset < protobuf.size) {
            val (tag, valueOffset) = readVarint(protobuf, offset) ?: return null
            val fieldNumber = tag ushr 3
            val wireType = (tag and 0x07).toInt()
            if (fieldNumber !in 1L..MAX_PROTOBUF_FIELD_NUMBER) return null

            if (fieldNumber == 1L) {
                if (wireType != 0) return null
                val (value, nextOffset) = readVarint(protobuf, valueOffset) ?: return null
                if (nextOffset <= valueOffset) return null
                if (value <= Int.MAX_VALUE && value.toInt() in 1..300) {
                    heartRate = value.toInt()
                }
                offset = nextOffset
            } else {
                offset = skipField(protobuf, valueOffset, wireType) ?: return null
            }
        }
        return ParsedWorkoutData(heartRate)
    }

    private fun skipField(data: ByteArray, offset: Int, wireType: Int): Int? {
        return when (wireType) {
            0 -> readVarint(data, offset)?.second
            1 -> (offset + 8).takeIf { it <= data.size }
            2 -> {
                val (length, valueOffset) = readVarint(data, offset) ?: return null
                if (length > Int.MAX_VALUE) return null
                (valueOffset + length.toInt()).takeIf { it >= valueOffset && it <= data.size }
            }
            5 -> (offset + 4).takeIf { it <= data.size }
            else -> null
        }
    }

    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var index = offset
        while (index < data.size && shift < Long.SIZE_BITS) {
            val current = data[index].unsigned()
            if (shift == 63 && current and 0x7e != 0) return null
            value = value or ((current and 0x7f).toLong() shl shift)
            index++
            if (current and 0x80 == 0) return value to index
            shift += 7
        }
        return null
    }

    private fun encodeVarint(value: Long): ByteArray {
        var remaining = value
        val bytes = ArrayList<Byte>(10)
        do {
            var current = (remaining and 0x7f).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) current = current or 0x80
            bytes += current.toByte()
        } while (remaining != 0L)
        return bytes.toByteArray()
    }

    private fun packetCrc(
        requestId: Int,
        control: Int,
        sid: Int,
        cid: Int,
        protobuf: ByteArray,
    ): Int {
        val logicalPacket = ByteArray(HEADER_SIZE + protobuf.size)
        logicalPacket[0] = requestId.toByte()
        logicalPacket[1] = (requestId ushr 8).toByte()
        logicalPacket[2] = control.toByte()
        logicalPacket[3] = protobuf.size.toByte()
        logicalPacket[4] = (protobuf.size ushr 8).toByte()
        logicalPacket[5] = sid.toByte()
        logicalPacket[6] = cid.toByte()
        protobuf.copyInto(logicalPacket, destinationOffset = HEADER_SIZE)
        return crc16Xmodem(logicalPacket)
    }

    private fun crc16Xmodem(data: ByteArray): Int {
        var crc = 0
        data.forEach { byte ->
            crc = crc xor (byte.unsigned() shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1 xor 0x1021) and 0xffff
                } else {
                    (crc shl 1) and 0xffff
                }
            }
        }
        return crc
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff
}
