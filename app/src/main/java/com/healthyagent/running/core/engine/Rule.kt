package com.healthyagent.running.core.engine

import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.SpeechPriority

/**
 * 规则接口 — 每条跑步指导规则实现此接口
 *
 * 普通规则的冷却由 [RuleEngine] 处理；需逐帧观察的规则可自行维护单调计时，
 * 并将 cooldownMs 设为 0，避免叠加外层门禁。
 */
interface Rule {
    /** 规则唯一标识 */
    val id: String

    /** 冷却时间（毫秒），0 表示无冷却（每次都可能触发），Long.MAX_VALUE 表示仅触发一次 */
    val cooldownMs: Long

    /** 默认播报优先级 */
    val defaultPriority: SpeechPriority

    /**
     * 评估规则条件
     *
     * @param ctx 评估上下文，包含历史帧序列、已运行时长等
     * @param config 跑步配置（目标配速/距离/最大心率等）
     * @return 触发结果（包含播报文本），条件不满足返回 null
     */
    fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger?

    /** 暂停只打断连续性；专用冷却保留到下次新跑步。 */
    fun onPause() {}

    /** 重置规则内部状态（在新跑步会话开始前调用），默认空实现 */
    fun reset() {}
}
