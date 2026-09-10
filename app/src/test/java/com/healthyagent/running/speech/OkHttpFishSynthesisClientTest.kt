package com.healthyagent.running.speech

import android.media.AudioAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OkHttpFishSynthesisClientTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `成功请求使用固定端点和安全字段并缓存完整 mp3`() = runBlocking {
        val responseBytes = "mp3-payload".encodeToByteArray()
        val factory = QueueCallFactory(
            { request -> FakeCall(request) { response(request, 200, TrackingResponseBody(responseBytes)) } },
        )
        val client = client(factory)

        val first = client.synthesize("保持轻松呼吸")
        val second = client.synthesize("保持轻松呼吸")

        assertEquals(1, factory.calls.size)
        val request = factory.calls.single().request()
        assertEquals("POST", request.method)
        assertEquals("https://api.fish.audio/v1/tts", request.url.toString())
        assertEquals("Bearer api-secret", request.header("Authorization"))
        assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
        val bodyBuffer = Buffer()
        request.body!!.writeTo(bodyBuffer)
        val json = Json.parseToJsonElement(bodyBuffer.readUtf8()).jsonObject
        assertEquals("保持轻松呼吸", json.getValue("text").jsonPrimitive.content)
        assertEquals("reference-1", json.getValue("reference_id").jsonPrimitive.content)
        assertEquals("mp3", json.getValue("format").jsonPrimitive.content)
        assertEquals(first, second)
        assertTrue(first.isFile)
        assertTrue(first.length() > 0)
        assertEquals(responseBytes.toList(), first.readBytes().toList())
        assertTrue(temporaryFolder.root.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test
    fun `不同 referenceId 的相同文本不共享缓存且文件名不暴露凭据`() = runBlocking {
        val firstFactory = QueueCallFactory(
            { request -> FakeCall(request) { response(request, 200, TrackingResponseBody("voice-a".encodeToByteArray())) } },
        )
        val secondFactory = QueueCallFactory(
            { request -> FakeCall(request) { response(request, 200, TrackingResponseBody("voice-b".encodeToByteArray())) } },
        )
        val firstClient = OkHttpFishSynthesisClient(
            apiKey = "api-secret",
            referenceId = "voice-reference-a",
            cacheDirectory = temporaryFolder.root,
            callFactory = firstFactory,
        )
        val secondClient = OkHttpFishSynthesisClient(
            apiKey = "api-secret",
            referenceId = "voice-reference-b",
            cacheDirectory = temporaryFolder.root,
            callFactory = secondFactory,
        )

        val first = firstClient.synthesize("相同文本")
        val second = secondClient.synthesize("相同文本")

        assertEquals(1, firstFactory.calls.size)
        assertEquals(1, secondFactory.calls.size)
        assertFalse(first == second)
        assertEquals("voice-a", first.readText())
        assertEquals("voice-b", second.readText())
        assertFalse(first.name.contains("api-secret"))
        assertFalse(second.name.contains("api-secret"))
    }

    @Test
    fun `下载中取消抛 CancellationException 并删除 partial`() = runBlocking {
        lateinit var call: FakeCall
        val readStarted = CountDownLatch(1)
        val factory = QueueCallFactory(
            { request ->
                FakeCall(request).also { created ->
                    call = created
                    created.executeBlock = {
                        response(
                            request,
                            200,
                            TrackingResponseBody(
                                BlockingInputStream(
                                    prefix = "partial".encodeToByteArray(),
                                    readStarted = readStarted,
                                    cancelled = created.cancelled,
                                ),
                            ),
                        )
                    }
                }
            },
        )
        val client = client(factory)
        val request = async(Dispatchers.Default) { client.synthesize("需要取消") }
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))

        client.cancel()

        val thrown = captureThrowable { withTimeout(5_000) { request.await() } }
        assertTrue(thrown is CancellationException)
        assertEquals(1, call.cancelCount.get())
        assertTrue(temporaryFolder.root.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test
    fun `newCall 发布前取消仍取消该操作且不执行 Call`() = runBlocking {
        val newCallEntered = CountDownLatch(1)
        val releaseNewCall = CountDownLatch(1)
        lateinit var lateCall: FakeCall
        val factory = Call.Factory { request ->
            newCallEntered.countDown()
            assertTrue(releaseNewCall.await(5, TimeUnit.SECONDS))
            FakeCall(request) {
                response(request, 200, TrackingResponseBody("不应执行".encodeToByteArray()))
            }.also { lateCall = it }
        }
        val client = client(factory)

        supervisorScope {
            val synthesis = async(Dispatchers.Default) { client.synthesize("发布前取消") }
            try {
                assertTrue(newCallEntered.await(5, TimeUnit.SECONDS))
                client.cancel()
            } finally {
                releaseNewCall.countDown()
            }

            val thrown = captureThrowable { withTimeout(5_000) { synthesis.await() } }
            assertTrue(thrown is CancellationException)
            assertEquals(1, lateCall.cancelCount.get())
            assertFalse(lateCall.isExecuted())
        }
    }

    @Test
    fun `旧请求 finally 不清除正在执行的新 Call`() = runBlocking {
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val newEntered = CountDownLatch(1)
        lateinit var newCall: FakeCall
        val factory = QueueCallFactory(
            { request ->
                FakeCall(request) {
                    oldEntered.countDown()
                    assertTrue(releaseOld.await(5, TimeUnit.SECONDS))
                    throw IOException("旧请求失败")
                }
            },
            { request ->
                FakeCall(request).also { created ->
                    newCall = created
                    created.executeBlock = {
                        newEntered.countDown()
                        assertTrue(created.cancelSignal.await(5, TimeUnit.SECONDS))
                        throw IOException("新请求已取消")
                    }
                }
            },
        )
        supervisorScope {
            val client = client(factory)
            val oldRequest = async(Dispatchers.Default) { client.synthesize("old") }
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
            val newRequest = async(Dispatchers.Default) { client.synthesize("new") }
            assertTrue(newEntered.await(5, TimeUnit.SECONDS))

            releaseOld.countDown()
            assertTrue(captureThrowable { withTimeout(5_000) { oldRequest.await() } } is IOException)
            client.cancel()

            assertEquals(1, newCall.cancelCount.get())
            assertTrue(captureThrowable { withTimeout(5_000) { newRequest.await() } } is CancellationException)
        }
    }

    @Test
    fun `相同文本重叠请求使用各自 partial 并都能原子发布缓存`() = runBlocking {
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseDownloads = CountDownLatch(1)
        val factory = QueueCallFactory(
            { request ->
                FakeCall(request) {
                    response(
                        request,
                        200,
                        TrackingResponseBody(
                            CoordinatedInputStream(
                                bytes = "first-audio".encodeToByteArray(),
                                entered = firstEntered,
                                release = releaseDownloads,
                            ),
                        ),
                    )
                }
            },
            { request ->
                FakeCall(request) {
                    response(
                        request,
                        200,
                        TrackingResponseBody(
                            CoordinatedInputStream(
                                bytes = "second-audio".encodeToByteArray(),
                                entered = secondEntered,
                                release = releaseDownloads,
                            ),
                        ),
                    )
                }
            },
        )
        val client = client(factory)

        supervisorScope {
            val first = async(Dispatchers.Default) { client.synthesize("重叠文本") }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) { client.synthesize("重叠文本") }
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))

            try {
                assertEquals(
                    2,
                    temporaryFolder.root.listFiles()
                        ?.count { file -> file.name.endsWith(".part") },
                )
            } finally {
                releaseDownloads.countDown()
            }

            assertTrue(withTimeout(5_000) { first.await() }.isFile)
            assertTrue(withTimeout(5_000) { second.await() }.isFile)
        }
        assertTrue(temporaryFolder.root.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test
    fun `等待缓存发布锁期间取消必须先于发布生效`() = runBlocking {
        val publishLock = requireNotNull(
            OkHttpFishSynthesisClient::class.java
                .getDeclaredField("CACHE_PUBLISH_LOCK")
                .apply { isAccessible = true }
                .get(null),
        )
        val lockHeld = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val lockHolder = Thread {
            synchronized(publishLock) {
                lockHeld.countDown()
                releaseLock.await(5, TimeUnit.SECONDS)
            }
        }
        val responseBody = TrackingResponseBody("不得发布".encodeToByteArray())
        val factory = QueueCallFactory(
            { request -> FakeCall(request) { response(request, 200, responseBody) } },
        )
        val client = client(factory)

        lockHolder.start()
        try {
            assertTrue(lockHeld.await(5, TimeUnit.SECONDS))
            supervisorScope {
                val synthesis = async(Dispatchers.Default) { client.synthesize("等待发布时取消") }
                assertTrue(responseBody.closeSignal.await(5, TimeUnit.SECONDS))
                assertTrue(
                    waitUntil(5_000) {
                        responseBody.closingThread?.state == Thread.State.BLOCKED
                    },
                )

                client.cancel()
                releaseLock.countDown()

                val thrown = captureThrowable { withTimeout(5_000) { synthesis.await() } }
                assertTrue(thrown is CancellationException)
            }
        } finally {
            releaseLock.countDown()
            lockHolder.join(5_000)
        }

        assertTrue(temporaryFolder.root.walkTopDown().none { it.extension == "mp3" })
        assertTrue(temporaryFolder.root.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test
    fun `非 2xx 只暴露状态类别并关闭响应体`() = runBlocking {
        val responseBody = TrackingResponseBody("TOP_SECRET_BODY".encodeToByteArray())
        val factory = QueueCallFactory(
            { request -> FakeCall(request) { response(request, 401, responseBody) } },
        )
        val client = client(factory)

        val thrown = captureThrowable { client.synthesize("鉴权失败") }

        assertTrue(thrown is SecurityException)
        val rendered = thrown.toString()
        assertTrue(rendered.contains("4xx"))
        assertFalse(rendered.contains("401"))
        assertFalse(rendered.contains("TOP_SECRET_BODY"))
        assertFalse(rendered.contains("api-secret"))
        assertFalse(rendered.contains("Authorization"))
        assertTrue(responseBody.closed.get())
    }

    @Test
    fun `空响应体失败并关闭响应体且不保留缓存`() = runBlocking {
        val responseBody = TrackingResponseBody(byteArrayOf())
        val factory = QueueCallFactory(
            { request -> FakeCall(request) { response(request, 200, responseBody) } },
        )
        val client = client(factory)

        val thrown = captureThrowable { client.synthesize("空响应") }

        assertTrue(thrown is IOException)
        assertTrue(responseBody.closed.get())
        assertTrue(temporaryFolder.root.walkTopDown().none { it.extension == "mp3" || it.name.endsWith(".part") })
    }

    @Test
    fun `Call 抛 Error 时原样冒泡而不被包装`() = runBlocking {
        val platformError = AssertionError("平台错误")
        val factory = QueueCallFactory(
            { request -> FakeCall(request) { throw platformError } },
        )
        val client = client(factory)

        val thrown = captureThrowable { client.synthesize("错误") }

        assertTrue(thrown is AssertionError)
        assertEquals(platformError.message, thrown?.message)
    }

    private fun client(factory: Call.Factory) = OkHttpFishSynthesisClient(
        apiKey = "api-secret",
        referenceId = "reference-1",
        cacheDirectory = temporaryFolder.root,
        callFactory = factory,
    )

    @Test
    fun `禁读缓存时同文历史音频也必须重新请求且失败不返回旧缓存`() = runBlocking {
        val factory = QueueCallFactory(
            { request -> FakeCall(request) { response(request, 200, TrackingResponseBody("历史音频".encodeToByteArray())) } },
            { request -> FakeCall(request) { response(request, 200, TrackingResponseBody("本次音频".encodeToByteArray())) } },
            { request -> FakeCall(request) { throw IOException("本次网络失败") } },
        )
        client(factory).synthesize("同文")
        val online = OkHttpFishSynthesisClient("api-secret", "reference-1", temporaryFolder.root, factory, readCache = false)
        assertEquals("本次音频", online.synthesize("同文").readText())
        assertTrue(captureThrowable { online.synthesize("同文") } is IOException)
        assertEquals(3, factory.calls.size)
    }

    private suspend fun captureThrowable(block: suspend () -> Any?): Throwable? = try {
        block()
        null
    } catch (error: Throwable) {
        error
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(1)
        }
        return condition()
    }

    private class QueueCallFactory(
        vararg builders: (Request) -> FakeCall,
    ) : Call.Factory {
        private val builders = ArrayDeque(builders.toList())
        val calls = mutableListOf<FakeCall>()

        override fun newCall(request: Request): Call {
            check(builders.isNotEmpty()) { "发生了未预期的 HTTP 请求" }
            return builders.removeFirst().invoke(request).also(calls::add)
        }
    }

    private class FakeCall(
        private val originalRequest: Request,
        execute: (FakeCall.() -> Response)? = null,
    ) : Call {
        val cancelled = AtomicBoolean(false)
        val cancelCount = AtomicInteger(0)
        val cancelSignal = CountDownLatch(1)
        var executeBlock: FakeCall.() -> Response = execute ?: { error("缺少 execute 行为") }
        private val executed = AtomicBoolean(false)

        override fun request(): Request = originalRequest

        override fun execute(): Response {
            executed.set(true)
            return executeBlock()
        }

        override fun enqueue(responseCallback: Callback) = error("本测试不使用 enqueue")

        override fun cancel() {
            cancelCount.incrementAndGet()
            cancelled.set(true)
            cancelSignal.countDown()
        }

        override fun isExecuted(): Boolean = executed.get()

        override fun isCanceled(): Boolean = cancelled.get()

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = FakeCall(originalRequest, executeBlock)
    }

    private class TrackingResponseBody : ResponseBody {
        val closed = AtomicBoolean(false)
        val closeSignal = CountDownLatch(1)
        @Volatile
        var closingThread: Thread? = null
        private val trackedSource: BufferedSource
        private val length: Long

        constructor(bytes: ByteArray) : this(bytes.inputStream(), bytes.size.toLong())

        constructor(input: InputStream, length: Long = -1L) {
            this.length = length
            trackedSource = object : ForwardingSource(input.source()) {
                override fun close() {
                    closingThread = Thread.currentThread()
                    closed.set(true)
                    closeSignal.countDown()
                    super.close()
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = "audio/mpeg".toMediaType()

        override fun contentLength(): Long = length

        override fun source(): BufferedSource = trackedSource
    }

    private class BlockingInputStream(
        private val prefix: ByteArray,
        private val readStarted: CountDownLatch,
        private val cancelled: AtomicBoolean,
    ) : InputStream() {
        private var index = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index < prefix.size) {
                val count = minOf(length, prefix.size - index)
                prefix.copyInto(buffer, offset, index, index + count)
                index += count
                if (index == prefix.size) readStarted.countDown()
                return count
            }
            repeat(500) {
                if (cancelled.get()) throw IOException("下载已取消")
                Thread.sleep(10)
            }
            throw IOException("等待取消超时")
        }
    }

    private class CoordinatedInputStream(
        private val bytes: ByteArray,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : InputStream() {
        private var delivered = false

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (delivered) return -1
            entered.countDown()
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw IOException("等待放行下载超时")
            }
            val count = minOf(length, bytes.size)
            bytes.copyInto(buffer, offset, 0, count)
            delivered = true
            return count
        }
    }

    private fun response(
        request: Request,
        code: Int,
        body: ResponseBody,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body)
        .build()
}

class AndroidAudioPlayerPortTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `助手语音使用 speech content type 和 assistant usage 并转发完成回调`() {
        val platform = FakeAudioPlayerPlatform()
        val port = AndroidAudioPlayerPort(platform)
        val callbacks = mutableListOf<String>()
        val audioFile = temporaryFolder.newFile("speech.mp3")

        val result = port.start(
            file = audioFile,
            profile = SpeechAudioProfile.ASSISTANT_SPEECH,
            listener = object : AudioPlayerPort.Listener {
                override fun onCompletion() {
                    callbacks += "completion"
                }

                override fun onError(what: Int, extra: Int) {
                    callbacks += "error:$what:$extra"
                }
            },
        )
        platform.capturedListener!!.onCompletion()
        platform.capturedListener!!.onError(7, 9)

        assertTrue(result.isSuccess)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, platform.contentType)
        assertEquals(AudioAttributes.USAGE_ASSISTANT, platform.usage)
        assertEquals(audioFile.absolutePath, platform.capturedDataSource)
        assertEquals(listOf("prepare", "start"), platform.calls)
        assertEquals(listOf("completion", "error:7:9"), callbacks)
    }

    @Test
    fun `平台普通异常映射为 failure 而 Error 原样冒泡`() {
        val ordinaryFailure = IOException("无法播放")
        val platform = FakeAudioPlayerPlatform(startFailure = ordinaryFailure)
        val port = AndroidAudioPlayerPort(platform)
        val audioFile = temporaryFolder.newFile("failure.mp3")
        val listener = NoOpAudioPlayerListener

        val result = port.start(audioFile, SpeechAudioProfile.ASSISTANT_SPEECH, listener)

        assertSame(ordinaryFailure, result.exceptionOrNull())

        val platformError = AssertionError("平台错误")
        platform.startFailure = platformError
        val thrown = try {
            port.start(audioFile, SpeechAudioProfile.ASSISTANT_SPEECH, listener)
            null
        } catch (error: AssertionError) {
            error
        }
        assertSame(platformError, thrown)
    }

    private object NoOpAudioPlayerListener : AudioPlayerPort.Listener {
        override fun onCompletion() = Unit
        override fun onError(what: Int, extra: Int) = Unit
    }

    private class FakeAudioPlayerPlatform(
        var startFailure: Throwable? = null,
    ) : AndroidAudioPlayerPlatform {
        var contentType: Int? = null
        var usage: Int? = null
        var capturedDataSource: String? = null
        var capturedListener: AndroidAudioPlayerListener? = null
        val calls = mutableListOf<String>()

        override fun setAudioAttributes(contentType: Int, usage: Int) {
            this.contentType = contentType
            this.usage = usage
        }

        override fun setDataSource(path: String) {
            capturedDataSource = path
        }

        override fun setListener(listener: AndroidAudioPlayerListener) {
            capturedListener = listener
        }

        override fun prepare() {
            calls += "prepare"
        }

        override fun start() {
            calls += "start"
            startFailure?.let { throw it }
        }

        override fun stop() = Unit
        override fun release() = Unit
    }
}
