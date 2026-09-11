package com.healthyagent.running.coordinator

import com.healthyagent.running.data.sensor.WearableLinkFault
import com.healthyagent.running.data.sensor.WearableLinkObservation
import com.healthyagent.running.data.sensor.WearableLinkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearableLinkAlertGateTest {
    private val ready = WearableLinkObservation(1, WearableLinkPhase.READY, 1, 0)
    private val lost = ready.copy(
        phase = WearableLinkPhase.RECOVERING,
        fault = WearableLinkFault(1, 1, 1_000),
    )

    @Test fun `连续故障恰满十秒才提交一次`() {
        val gate = WearableLinkAlertGate().apply { reset(ready) }
        assertNull(gate.evaluate(lost, 10_999))
        assertEquals(lost.fault, gate.evaluate(lost, 11_000))
        assertNull(gate.evaluate(lost, 60_000))
    }

    @Test fun `初始恢复基线不回放旧故障`() {
        val gate = WearableLinkAlertGate().apply { reset(lost) }
        assertNull(gate.evaluate(lost, 60_000))
        val next = lost.copy(readyRevision = 2, fault = WearableLinkFault(2, 1, 61_000))
        assertEquals(next.fault, gate.evaluate(next, 71_000))
    }

    @Test fun `StateFlow吞掉瞬时Ready仍保留合法故障资格`() {
        val gate = WearableLinkAlertGate().apply { reset(WearableLinkObservation()) }
        assertEquals(lost.fault, gate.evaluate(lost, 11_000))
    }

    @Test fun `已通知后短暂恢复的连续新故障不会再次播报`() {
        val gate = WearableLinkAlertGate().apply { reset(ready) }
        assertEquals(lost.fault, gate.evaluate(lost, 11_000))
        assertNull(gate.evaluate(ready.copy(readyRevision = 2, readySinceMillis = 12_000), 12_000))
        val next = lost.copy(readyRevision = 2, fault = WearableLinkFault(2, 1, 13_000))
        assertNull(gate.evaluate(next, 23_000))
    }

    @Test fun `未通知时短暂恢复后必须重新连续十秒`() {
        val gate = WearableLinkAlertGate().apply { reset(ready) }
        assertNull(gate.evaluate(lost, 5_000))
        val next = lost.copy(readyRevision = 2, fault = WearableLinkFault(2, 1, 6_000))
        assertNull(gate.evaluate(next, 15_999))
        assertEquals(next.fault, gate.evaluate(next, 16_000))
    }

    @Test fun `稳定恢复建立新episode即使跳过Ready也能通知`() {
        val gate = WearableLinkAlertGate().apply { reset(ready) }
        gate.evaluate(lost, 11_000)
        val next = lost.copy(readyRevision = 2, fault = WearableLinkFault(2, 2, 30_000))
        assertEquals(next.fault, gate.evaluate(next, 40_000))
    }

    @Test fun `无Ready资格与过期观察均不提交`() {
        val gate = WearableLinkAlertGate().apply { reset(ready.copy(generation = 2)) }
        assertNull(gate.evaluate(lost, 60_000))
        assertNull(gate.evaluate(lost.copy(generation = 3, readyRevision = 0), 60_000))
    }

    @Test fun `新观察代次的故障身份不与旧会话重复`() {
        val gate = WearableLinkAlertGate().apply { reset(ready) }
        gate.evaluate(lost, 11_000)
        val next = lost.copy(generation = 2)
        assertEquals(next.fault, gate.evaluate(next, 11_000))
    }
}
