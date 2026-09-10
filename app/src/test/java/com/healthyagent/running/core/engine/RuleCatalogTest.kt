package com.healthyagent.running.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleCatalogTest {

    @Test
    fun returnsElevenUniqueRulesInStableOrder() {
        val ids = RuleCatalog.defaultRules().map(Rule::id)

        assertEquals(
            listOf(
                "hr_z5_danger",
                "hr_rapid_rise",
                "hr_too_low",
                "cadence_drop",
                "cadence_too_high",
                "pace_deviation",
                "start_too_fast",
                "efficiency_abnormal",
                "data_stale",
                "km_split",
                "goal_achieved",
            ),
            ids,
        )
        assertEquals(11, ids.distinct().size)
    }
}
