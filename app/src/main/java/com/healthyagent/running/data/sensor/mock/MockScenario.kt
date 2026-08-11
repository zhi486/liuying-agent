package com.healthyagent.running.data.sensor.mock

/**
 * 模拟跑步场景 — 定义时间线上每个时刻的目标指标
 */
data class ScenarioPoint(
    val elapsedSec: Long,
    val hr: Int,             // 心率 bpm
    val cadence: Int,        // 步频 spm
    val paceSecPerKm: Double? = null,  // 配速（秒/公里），null 表示该时刻无配速基准
)

/**
 * 模拟场景脚本
 */
sealed class MockScenario(val name: String) {

    /** 返回指定秒数的场景数据点 */
    abstract fun pointAt(elapsedSec: Long): ScenarioPoint

    /**
     * 轻松跑：心率在 Z1-Z2，稳定配速 6:00/km
     */
    data object EASY_RUN : MockScenario("轻松跑") {
        override fun pointAt(elapsedSec: Long): ScenarioPoint {
            val hr = 110 + (elapsedSec / 60).toInt().coerceIn(0, 15) // 逐渐上升到 125
            return ScenarioPoint(
                elapsedSec = elapsedSec,
                hr = hr,
                cadence = 170 + ((elapsedSec / 30) % 3).toInt(), // 170-172 波动
                paceSecPerKm = 360.0  // 6:00/km
            )
        }
    }

    /**
     * 渐加速跑：前 5 分钟热身，然后每公里提速
     */
    data object PROGRESSIVE : MockScenario("渐加速跑") {
        override fun pointAt(elapsedSec: Long): ScenarioPoint {
            val minutes = elapsedSec / 60
            val hr = when {
                minutes < 5 -> 100 + (minutes * 4).toInt()             // 热身：100→120
                minutes < 15 -> 130 + ((minutes - 5) * 2).toInt()      // 稳定跑：130→150
                else -> 150 + ((minutes - 15)).toInt().coerceIn(0, 20)  // 加速：150→170
            }
            val pace = when {
                minutes < 5 -> 390.0   // 6:30/km
                minutes < 15 -> 360.0  // 6:00/km
                else -> 330.0          // 5:30/km
            }
            return ScenarioPoint(
                elapsedSec = elapsedSec,
                hr = hr,
                cadence = 172 + (minutes / 5).toInt().coerceIn(0, 10),
                paceSecPerKm = pace
            )
        }
    }

    /**
     * 心率飙升应急场景：正常跑 → 突然心率飙升 → 模拟用户减速恢复
     */
    data object HEART_RATE_SPIKE : MockScenario("心率飙升") {
        override fun pointAt(elapsedSec: Long): ScenarioPoint {
            val phase = elapsedSec / 30  // 每 30s 一个阶段
            val hr = when {
                elapsedSec < 120 -> 130                              // 0-2min：正常
                elapsedSec in 120..149 -> 130 + ((elapsedSec - 120) * 3).toInt()  // 2-2.5min：快速上升
                elapsedSec in 150..179 -> 185                        // 2.5-3min：维持高位
                else -> 185 - ((elapsedSec - 180) / 2).toInt().coerceIn(0, 50)     // 3min+：逐步恢复
            }
            return ScenarioPoint(
                elapsedSec = elapsedSec,
                hr = hr,
                cadence = if (elapsedSec in 120..179) 165 else 172,  // 心率飙升时段步频下降
                paceSecPerKm = 360.0
            )
        }
    }

    /**
     * 步频骤降场景：模拟长距离后期疲劳导致的步频下降
     */
    data object CADENCE_DROP : MockScenario("步频骤降") {
        override fun pointAt(elapsedSec: Long): ScenarioPoint {
            val minutes = elapsedSec / 60
            val cadence = when {
                minutes < 15 -> 175                          // 正常
                minutes in 15..20 -> 175 - (minutes - 15) * 3  // 逐步下降
                else -> 155                                  // 维持低位
            }
            return ScenarioPoint(
                elapsedSec = elapsedSec,
                hr = 140 + (minutes / 2).toInt().coerceIn(0, 20),
                cadence = cadence,
                paceSecPerKm = 360.0
            )
        }
    }
}
