package com.healthyagent.running.data.sensor.ble

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class KeyedConflatedEventQueue<K : Any, V : Any>(
    private val merge: (pending: V, incoming: V) -> V = { _, incoming -> incoming },
) {
    private val lock = Any()
    private val pending = LinkedHashMap<K, V>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val collectorClaimed = AtomicBoolean(false)
    private var closed = false

    val values: Flow<V> = flow {
        check(collectorClaimed.compareAndSet(false, true)) {
            "KeyedConflatedEventQueue only supports one collector"
        }
        while (true) {
            when (val result = poll()) {
                is PollResult.Value -> emit(result.value)
                PollResult.Done -> return@flow
                PollResult.Wait -> wakeups.receive()
            }
        }
    }

    fun offer(key: K, value: V): Boolean {
        synchronized(lock) {
            if (closed) return false
            val current = pending[key]
            pending[key] = if (current == null) value else merge(current, value)
        }
        wakeups.trySend(Unit)
        return true
    }

    fun close() {
        val changed = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (changed) wakeups.trySend(Unit)
    }

    private fun poll(): PollResult<V> = synchronized(lock) {
        val iterator = pending.entries.iterator()
        when {
            iterator.hasNext() -> {
                val value = iterator.next().value
                iterator.remove()
                PollResult.Value(value)
            }
            closed -> PollResult.Done
            else -> PollResult.Wait
        }
    }

    private sealed interface PollResult<out V> {
        data class Value<V>(val value: V) : PollResult<V>
        data object Done : PollResult<Nothing>
        data object Wait : PollResult<Nothing>
    }
}
