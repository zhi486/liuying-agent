package com.healthyagent.running.speech

object KilometerSpeechFormatter {
    fun format(index: Int, durationSec: Long, paceSecPerKm: Double): String {
        val paceClause = if (paceSecPerKm.isFinite() && paceSecPerKm > 0.0) {
            "，配速每公里${formatSeconds(paceSecPerKm.toLong())}"
        } else {
            ""
        }
        return "第${formatNumber(index.toLong())}公里，用时${formatSeconds(durationSec)}${paceClause}。"
    }

    private fun formatSeconds(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return when {
            minutes == 0L -> "${formatNumber(seconds)}秒"
            seconds == 0L -> "${formatNumber(minutes)}分整"
            seconds < 10L -> "${formatNumber(minutes)}分零${formatNumber(seconds)}秒"
            else -> "${formatNumber(minutes)}分${formatNumber(seconds)}秒"
        }
    }

    private fun formatNumber(value: Long): String = when {
        value < 10 -> DIGITS[value.toInt()]
        value < 20 -> "十${if (value == 10L) "" else DIGITS[(value % 10).toInt()]}"
        value < 100 -> "${DIGITS[(value / 10).toInt()]}十${if (value % 10 == 0L) "" else DIGITS[(value % 10).toInt()]}"
        value < 1_000 -> formatWithUnit(value, 100, "百")
        value < 10_000 -> formatWithUnit(value, 1_000, "千")
        else -> value.toString()
    }

    private fun formatWithUnit(value: Long, unitValue: Long, unit: String): String {
        val remainder = value % unitValue
        if (remainder == 0L) return "${formatNumber(value / unitValue)}$unit"
        val separator = if (remainder < unitValue / 10) "零" else ""
        val remainderText = if (remainder in 10..19) {
            "一${formatNumber(remainder)}"
        } else {
            formatNumber(remainder)
        }
        return "${formatNumber(value / unitValue)}$unit$separator$remainderText"
    }

    private val DIGITS = listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
}
