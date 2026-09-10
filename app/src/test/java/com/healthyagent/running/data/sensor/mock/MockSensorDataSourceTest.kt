package com.healthyagent.running.data.sensor.mock

import com.healthyagent.running.core.model.SampleType
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class MockSensorDataSourceTest {
    @Test
    fun `暂停后重新收集样本会延续模拟场景累计值`() = runTest {
        val source = MockSensorDataSource()
        source.connect()

        val firstSegment = source.samples
            .filter { it.type == SampleType.DISTANCE }
            .take(2)
            .toList()
        val resumedSegment = source.samples
            .filter { it.type == SampleType.DISTANCE }
            .take(1)
            .toList()

        assertTrue(resumedSegment.single().value > firstSegment.last().value)
    }
}
