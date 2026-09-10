package com.healthyagent.running.core.engine

import com.healthyagent.running.core.engine.rules.CadenceDropRule
import com.healthyagent.running.core.engine.rules.CadenceTooHighRule
import com.healthyagent.running.core.engine.rules.DataStaleRule
import com.healthyagent.running.core.engine.rules.EfficiencyAbnormalRule
import com.healthyagent.running.core.engine.rules.GoalAchievedRule
import com.healthyagent.running.core.engine.rules.HrRapidRiseRule
import com.healthyagent.running.core.engine.rules.HrTooLowRule
import com.healthyagent.running.core.engine.rules.HrZone5Rule
import com.healthyagent.running.core.engine.rules.KmSplitRule
import com.healthyagent.running.core.engine.rules.PaceDeviationRule
import com.healthyagent.running.core.engine.rules.StartTooFastRule
import kotlin.time.TimeSource

object RuleCatalog {
    fun defaultRules(timeSource: TimeSource = TimeSource.Monotonic): List<Rule> = listOf(
        HrZone5Rule(timeSource),
        HrRapidRiseRule(),
        HrTooLowRule(),
        CadenceDropRule(),
        CadenceTooHighRule(),
        PaceDeviationRule(),
        StartTooFastRule(),
        EfficiencyAbnormalRule(),
        DataStaleRule(),
        KmSplitRule(),
        GoalAchievedRule(),
    )
}
