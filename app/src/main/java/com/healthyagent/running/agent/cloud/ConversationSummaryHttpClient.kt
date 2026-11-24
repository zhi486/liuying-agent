package com.healthyagent.running.agent.cloud

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

/** 尚未装配；调用方须先持久领取，只将原请求的 QUERY 用于恢复。 */
internal class ConversationSummaryHttpClient(
    private val credentials: CloudCredentialSource,
    private val calls: Call.Factory,
) {
    class Receipt(val state: String, val remoteFinished: Boolean,
        val usage: ConversationSummaryProtocol.Usage?, private val summary: String?, private val fingerprint: String,
        private val requestId: String) {
        internal fun result(id: String, hash: String, current: ConversationSummaryPolicy.Candidate?): ConversationSummaryProtocol.Result {
            require(id == requestId && hash == fingerprint)
            return when (state) {
                "withdrawn" -> ConversationSummaryProtocol.Result.Withdrawn(remoteFinished, usage)
                "failed" -> ConversationSummaryProtocol.Result.Failed(usage)
                "completed" -> acceptedSummary(current)?.let {
                    ConversationSummaryProtocol.Result.Completed(it, requireNotNull(usage))
                } ?: ConversationSummaryProtocol.Result.Failed(usage)
                else -> ConversationSummaryProtocol.Result.Unknown
            }
        }
        /** current 必须由宿主在实际接纳时重新构造，不能复用提交时的候选。 */
        fun acceptedSummary(current: ConversationSummaryPolicy.Candidate?): String? =
            summary.takeIf { current?.fingerprint == fingerprint }
        override fun toString() = "Receipt(摘要已隐藏)"
    }

    suspend fun execute(identity: CloudTaskIdentity, step: ConversationSummaryProtocol.Step,
        candidate: ConversationSummaryPolicy.Candidate?): Receipt = withContext(Dispatchers.IO) {
        val snapshot = step.snapshot
        val action = requireNotNull(step.action)
        val body = if (action == ConversationSummaryProtocol.Action.SUBMIT) {
            require(snapshot.state == ConversationSummaryProtocol.State.ATTEMPTED &&
                candidate?.fingerprint == snapshot.fingerprint)
            buildJsonObject {
                put("version", "summary_v1"); put("requestId", snapshot.requestId)
                put("fingerprint", snapshot.fingerprint); put("approved", true)
                put("sources", JsonArray(requireNotNull(candidate).sources.map { source -> buildJsonObject {
                    put("requestId", source.requestId); put("prompt", source.prompt); put("answer", source.answer)
                } }))
            }.toString().toByteArray(Charsets.UTF_8)
        } else null
        val bearer = credentials.read(identity)
        val url = "${identity.origin}/v1/agent/summaries" +
            if (body == null) "/${snapshot.requestId}" else ""
        val builder = Request.Builder().url(url).header("Authorization", "Bearer ${bearer.value}")
            .header("Cache-Control", "no-store").header("Accept-Encoding", "identity")
            .tag(AtomicBoolean::class.java, AtomicBoolean(false))
        if (body != null) builder.post(object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun isOneShot() = true
            override fun contentLength() = body.size.toLong()
            override fun writeTo(sink: BufferedSink) { sink.write(body) }
        })
        val request = builder.build()
        coroutineContext.ensureActive()
        try {
            suspendCancellableCoroutine<Receipt> { continuation ->
                val call = calls.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val receipt = response.use {
                                check(response.code == 200 && response.request.url == request.url &&
                                    response.header("Content-Encoding")?.lowercase()?.let { it != "identity" } != true)
                                val responseBody = checkNotNull(response.body)
                                val media = responseBody.contentType()
                                check(media?.type == "application" && media.subtype == "json" &&
                                    media.parameter("charset")?.lowercase()?.let { it != "utf-8" } != true)
                                val bytes = responseBody.byteStream().use { it.readCloudBytes(20000) }
                                val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                                parse(text, snapshot.requestId, snapshot.fingerprint)
                            }
                            continuation.resume(receipt)
                        } catch (error: Exception) { continuation.resumeWithException(error) }
                    }
                })
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw IOException("摘要服务暂不可用；仅查询原请求") }
    }

    companion object {
        internal fun parse(text: String, requestId: String, fingerprint: String): Receipt {
            require(text.length <= 20000)
            val row = Json.parseToJsonElement(text).jsonObject
            require(row.keys == setOf("version", "purpose", "requestId", "fingerprint", "state",
                "remoteFinished", "summary", "cost", "usage"))
            fun string(name: String): String {
                val value = row[name] as? JsonPrimitive ?: error("摘要回执无效")
                require(value.isString); return value.content
            }
            require(string("version") == "summary_v1" && string("purpose") == "conversation_summary" &&
                string("requestId") == requestId && string("fingerprint") == fingerprint)
            val finished = row["remoteFinished"] as? JsonPrimitive ?: error("摘要回执无效")
            require(!finished.isString)
            val remoteFinished = requireNotNull(finished.booleanOrNull)
            val usage = row["usage"].takeUnless { it == JsonNull }?.let { value ->
                val fields = value.jsonObject
                require(fields.keys == setOf("inputTokens", "outputTokens"))
                fun count(name: String): Long {
                    val number = fields[name] as? JsonPrimitive ?: error("用量无效")
                    require(!number.isString && number.content.matches(Regex("0|[1-9][0-9]*")))
                    return requireNotNull(number.longOrNull).also { require(it in 0..9007199254740991L) }
                }
                ConversationSummaryProtocol.Usage(count("inputTokens"), count("outputTokens"))
            }
            require(string("cost") == if (usage == null) "uncertain" else "usage")
            val summary = row["summary"].takeUnless { it == JsonNull }?.let {
                require(it is JsonPrimitive && it.isString && CloudTaskIntent.supportedPrompt(it.content, 2000))
                it.content
            }
            val state = string("state")
            require(state in setOf("attempted", "completed", "failed", "withdrawn"))
            require((state == "completed") == (summary != null))
            require(state != "completed" || (remoteFinished && usage != null))
            require(state != "failed" || remoteFinished)
            require(state != "attempted" || (!remoteFinished && usage == null))
            return Receipt(state, remoteFinished, usage, summary, fingerprint, requestId)
        }
    }
}
