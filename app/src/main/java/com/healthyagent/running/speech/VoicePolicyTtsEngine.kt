package com.healthyagent.running.speech

import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticFailure
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.SafeDiagnosticSummary
import com.healthyagent.running.diagnostics.TtsEventKind
import com.healthyagent.running.diagnostics.TtsProvider
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class VoicePolicyTtsEngine(
    private val bundledStore: BundledVoiceStore,
    private val onlineFactory: () -> FishSynthesisClient?,
    private val companionTextSource: CompanionTextSource,
    private val playerFactory: () -> VoiceFilePlayer,
    private val diagnosticLogger: DiagnosticLogger,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : TtsEngine {
    private val lock = Any()
    private var active: Operation? = null
    private var closed = false

    override val isSpeaking: Boolean
        get() = synchronized(lock) { active?.let { it.playing && it.job.isActive } == true }

    override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> {
        currentCoroutineContext().ensureActive()
        return Result.failure(IllegalArgumentException("语音播报需要规则标识"))
    }

    override suspend fun speak(request: SpeechRequest): Result<SpeechDelivery> {
        val context = currentCoroutineContext()
        context.ensureActive()
        val acceptedRequest = if (request.preparationAcceptedAt != null) request
            else request.copy(preparationAcceptedAt = timeSource.markNow())
        val operation = Operation(Job(context[Job]))
        val previous = synchronized(lock) {
            if (closed) {
                operation.job.complete()
                return Result.failure(IllegalStateException("语音引擎已关闭"))
            }
            active.also { active = operation }
        }
        previous?.let(::cancel)
        try {
            return withContext(operation.job) {
                val ruleId = request.trigger.ruleId
                val bundled = BundledVoiceCatalog.find(ruleId)
                val core = bundled?.text ?: request.trigger.speakText
                val localOnly = request.trigger.ttsPriority == SpeechPriority.URGENT || ruleId == "data_stale"
                val kilometer = ruleId == "km_split"
                val online = if (localOnly) null else prepareOnline(operation, acceptedRequest, core, kilometer)
                currentCoroutineContext().ensureActive()
                val prepared = online ?: run {
                    if (kilometer) {
                        record(TtsEventKind.SKIPPED_OFFLINE)
                        return@withContext Result.success(SpeechDelivery(core, played = false))
                    }
                    val file = if (bundled == null) null else bundledStore.open(ruleId)
                    currentCoroutineContext().ensureActive()
                    if (file == null) {
                        record(TtsEventKind.FAILED, provider = TtsProvider.BUNDLED)
                        return@withContext Result.failure(IllegalStateException("固定语音资源不可用"))
                    }
                    if (!localOnly) record(TtsEventKind.LOCAL_FALLBACK, provider = TtsProvider.BUNDLED)
                    prepareAudio(operation, Prepared(file, core, VoiceAudioSource.BUNDLED))
                }
                val player = checkNotNull(operation.player)
                currentCoroutineContext().ensureActive()
                request.noticeToken?.ensureValid()
                synchronized(lock) {
                    if (active !== operation) throw CancellationException("语音操作已失效")
                    operation.playing = true
                }
                val result = player.play()
                currentCoroutineContext().ensureActive()
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                result.map { SpeechDelivery(prepared.text, played = true) }
            }
        } catch (error: CancellationException) {
            cancel(operation)
            if (!operation.playing) record(TtsEventKind.CANCELLED, provider =
                if (request.trigger.ttsPriority == SpeechPriority.URGENT || request.trigger.ruleId == "data_stale") TtsProvider.BUNDLED else TtsProvider.FISH)
            throw error
        } catch (error: Exception) {
            return Result.failure(error)
        } finally {
            synchronized(lock) { if (active === operation) active = null }
            runCatching { operation.client?.shutdown() }
            runCatching { operation.player?.shutdown() }
            operation.job.complete()
        }
    }

    private suspend fun prepareOnline(operation: Operation, request: SpeechRequest, core: String, kilometer: Boolean): Prepared? {
        if (remainingPreparationMillis(request, kilometer) == 0L) {
            record(TtsEventKind.PREPARATION_TIMEOUT)
            return null
        }
        try {
            val client = onlineFactory()
            synchronized(lock) { operation.client = client }
            currentCoroutineContext().ensureActive()
            if (client == null) {
                record(TtsEventKind.SKIPPED_MISSING_CONFIG)
                return null
            }
            record(TtsEventKind.ATTEMPTED)
            val prepared = withTimeoutOrNull(remainingPreparationMillis(request, kilometer)) {
                val companion = if (request.companionEnabled) {
                    try {
                        withTimeoutOrNull(minOf(2_000L, remainingPreparationMillis(request, kilometer))) {
                            awaitPreparation { companionTextSource.create(request) }
                        }
                    } catch (_: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        null
                    } catch (_: Exception) {
                        null
                    }
                } else null
                if (remainingPreparationMillis(request, kilometer) == 0L) return@withTimeoutOrNull null
                val suffix = companion?.trim().orEmpty()
                val separator = if (suffix.isNotEmpty() && core.lastOrNull() !in setOf('。', '！', '？', '.', '!', '?')) "。" else ""
                val text = core + separator + suffix
                val file = awaitPreparation(onCancel = client::cancel) { client.synthesize(text) }
                if (remainingPreparationMillis(request, kilometer) == 0L) return@withTimeoutOrNull null
                prepareAudio(operation, Prepared(file, text, VoiceAudioSource.ONLINE))
                    .takeIf { remainingPreparationMillis(request, kilometer) > 0L }
            }
            if (prepared == null) {
                runCatching { client.cancel() }
                discardPlayer(operation)
                record(TtsEventKind.PREPARATION_TIMEOUT)
            }
            return prepared
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            discardPlayer(operation)
            record(TtsEventKind.FAILED, error)
            return null
        }
    }

    private fun remainingPreparationMillis(request: SpeechRequest, kilometer: Boolean): Long {
        val limit = (if (kilometer) 15_000L else 8_000L).milliseconds
        return (limit - checkNotNull(request.preparationAcceptedAt).elapsedNow()).inWholeMilliseconds
            .coerceIn(0L, limit.inWholeMilliseconds)
    }

    private suspend fun prepareAudio(operation: Operation, prepared: Prepared): Prepared {
        val player = playerFactory()
        synchronized(lock) { operation.player = player }
        currentCoroutineContext().ensureActive()
        player.prepare(prepared.file, prepared.source).getOrThrow()
        currentCoroutineContext().ensureActive()
        return prepared
    }

    private fun discardPlayer(operation: Operation) {
        val player = synchronized(lock) { operation.player.also { operation.player = null } }
        runCatching { player?.shutdown() }
    }

    // 阻塞或忽略取消的服务不能拖住调用方；迟到结果只能完成已经取消的等待。
    private suspend fun <T> awaitPreparation(onCancel: () -> Unit = {}, block: suspend () -> T): T {
        val context = currentCoroutineContext()
        return suspendCancellableCoroutine { continuation ->
            val workerJob = Job()
            val worker = CoroutineScope(context + workerJob).launch(start = CoroutineStart.LAZY) {
                try { continuation.resumeWith(runCatching { block() }) } finally { workerJob.complete() }
            }
            continuation.invokeOnCancellation {
                runCatching(onCancel)
                workerJob.cancel()
            }
            worker.start()
        }
    }

    override fun stop() {
        val operation = synchronized(lock) { active.also { active = null } }
        operation?.let(::cancel)
    }

    override fun shutdown() {
        val operation = synchronized(lock) { closed = true; active.also { active = null } }
        operation?.let(::cancel)
    }

    private fun cancel(operation: Operation) {
        operation.job.cancel(CancellationException("语音操作已取消"))
        runCatching { operation.client?.cancel() }
        runCatching { operation.player?.stop() }
    }

    private fun record(kind: TtsEventKind, error: Exception? = null, provider: TtsProvider = TtsProvider.FISH) {
        runCatching {
            diagnosticLogger.record(DiagnosticEvent.TtsProviderEvent(provider, kind,
                error?.let { DiagnosticFailure.from(it, SafeDiagnosticSummary.FISH_SYNTHESIS_FAILED) }))
        }
    }

    private data class Prepared(val file: File, val text: String, val source: VoiceAudioSource)
    private class Operation(val job: kotlinx.coroutines.CompletableJob) {
        var client: FishSynthesisClient? = null
        var player: VoiceFilePlayer? = null
        var playing = false
    }
}
