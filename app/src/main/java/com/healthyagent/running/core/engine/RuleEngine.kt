package com.healthyagent.running.core.engine

import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.NoOpDiagnosticLogger

/**
 * 本地规则引擎 — 每帧评估全部规则，统一管理冷却
 *
 * 纯 Kotlin，零 Android 依赖，JVM 可直接单测。
 * 每帧评估耗时目标 < 5ms（11 条规则并行条件判断）。
 */
class RuleEngine(
    private val rules: List<Rule>,
    initialConfig: RunConfig,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
) {
    @Volatile
    private var config = initialConfig

    /** 仅在新跑步开始前替换规则参数。 */
    fun updateConfig(config: RunConfig) {
        this.config = config
    }

    /** 每条规则的上次触发时间戳（毫秒） */
    private val lastTriggerTimes = mutableMapOf<String, Long>()

    /** 每条规则当前冷却窗口已记录的触发时间戳。 */
    private val lastLoggedCooldownTriggerAt = mutableMapOf<String, Long>()

    /** 每公里需要重置冷却的规则 */
    private val splitResetRules = setOf("efficiency_abnormal", "km_split")

    /** 当前分段索引 */
    private var lastSplitIndex = 0

    /**
     * 对一帧指标评估所有规则
     *
     * @param frame 当前帧
     * @param context 评估上下文（应包含截止上一帧的历史）
     * @return 触发的规则列表（已过滤冷却）
     */
    fun evaluate(frame: MetricsFrame, context: RuleContext): List<RuleTrigger> {
        val now = nowMillis()
        val triggers = mutableListOf<RuleTrigger>()

        // 将当前帧追加到上下文中
        val updatedFrames = (context.recentFrames + frame).takeLast(MAX_HISTORY)
        val updatedContext = context.copy(
            recentFrames = updatedFrames,
            completedSplit = context.completedSplit,
            lastTriggerTimes = lastTriggerTimes.toMap(),
        )

        for (rule in rules) {
            // 检查冷却
            if (isInCooldown(rule, now)) {
                recordCooldownSkip(rule)
                continue
            }

            val trigger = rule.evaluate(updatedContext, config) ?: continue

            lastTriggerTimes[rule.id] = now
            diagnosticLogger.record(DiagnosticEvent.RuleTriggered(rule.id))
            triggers.add(trigger)
        }

        return triggers
    }

    /**
     * 每公里分段事件 — 重置需要每公里触发的规则的冷却
     */
    fun onSplit(splitIndex: Int) {
        splitResetRules.forEach {
            lastTriggerTimes.remove(it)
            lastLoggedCooldownTriggerAt.remove(it)
        }
        lastSplitIndex = splitIndex
    }

    /** 获取当前分段索引 */
    fun currentSplitIndex(): Int = lastSplitIndex

    /** 暂停不等于新会话，保留各规则的冷却。 */
    fun onPause() {
        rules.forEach { it.onPause() }
    }

    /** 重置引擎状态（新跑步会话开始前调用） */
    fun reset() {
        lastTriggerTimes.clear()
        lastLoggedCooldownTriggerAt.clear()
        lastSplitIndex = 0
        rules.forEach { it.reset() }
    }

    private fun isInCooldown(rule: Rule, now: Long): Boolean {
        val last = lastTriggerTimes[rule.id] ?: return false
        // cooldownMs == 0 表示无冷却，不跳过
        if (rule.cooldownMs == 0L) return false
        return (now - last) < rule.cooldownMs
    }

    private fun recordCooldownSkip(rule: Rule) {
        val lastTriggerTime = lastTriggerTimes.getValue(rule.id)
        if (lastLoggedCooldownTriggerAt[rule.id] == lastTriggerTime) return

        lastLoggedCooldownTriggerAt[rule.id] = lastTriggerTime
        diagnosticLogger.record(
            DiagnosticEvent.RuleCooldownSkipped(
                ruleId = rule.id,
                windowStartedAtMillis = lastTriggerTime,
            ),
        )
    }

    companion object {
        private const val MAX_HISTORY = 60
    }
}
