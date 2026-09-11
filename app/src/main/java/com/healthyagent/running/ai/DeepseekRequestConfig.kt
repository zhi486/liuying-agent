package com.healthyagent.running.ai

import com.healthyagent.running.core.model.CoachTone

class DeepseekRequestConfig(internal val apiKey: String, val coachTone: CoachTone) {
    override fun toString() = "DeepseekRequestConfig(凭据已隐藏)"
}
