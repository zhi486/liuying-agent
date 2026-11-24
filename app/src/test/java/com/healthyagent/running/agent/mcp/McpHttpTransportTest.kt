package com.healthyagent.running.agent.mcp

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.Timeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class McpHttpTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var tls: HandshakeCertificates
    private val endpoint = McpEndpoint.parse("https://tools.example.com/mcp")
    private val publicAddress = InetAddress.getByName("93.184.215.14")
    private val credential = McpBearer("synthetic-plugin-key")
    private val connected = mutableListOf<InetSocketAddress>()
    private var lookups = 0
    private var secretReads = 0

    @Before fun setUp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("tools.example.com").build()
        tls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            start()
        }
    }
    @After fun tearDown() { server.shutdown() }

    // 仅测试替换底层 Socket：保留 DNS 策略、原域名、真实 TLS 和全部生产拦截器，绝不访问公网。
    private val sockets = object : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(address: SocketAddress, timeout: Int) {
                connected.add(address as InetSocketAddress)
                super.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.port), timeout)
            }
        }
        override fun createSocket(host: String, port: Int): Socket = error("测试不允许重新按主机解析")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("测试不允许重新按主机解析")
        override fun createSocket(address: InetAddress, port: Int): Socket = error("测试不允许隐式建连")
        override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("测试不允许隐式建连")
    }
    private fun resolver(resolve: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String) = resolve(hostname)
    }
    private fun transport(dns: Dns = resolver { lookups++; listOf(publicAddress) }, target: McpEndpoint = endpoint,
        timeoutMillis: Long = 2000, source: McpCredentialSource = McpCredentialSource { secretReads++; credential }): McpHttpTransport {
        val client = McpHttpTransport.clientBuilder(target, dns).socketFactory(sockets)
            .sslSocketFactory(tls.sslSocketFactory(), tls.trustManager).readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
        return McpHttpTransport(target, source, client)
    }
    private fun json(text: String = "{}") = MockResponse().setHeader("Content-Type", "application/json").setBody(text)
    private suspend fun failure(block: suspend () -> Unit): McpTransportFailure {
        try { block() } catch (error: McpTransportFailure) {
            assertEquals("插件连接未完成", error.message)
            assertNull(error.cause)
            return error
        }
        error("应拒绝该连接或响应")
    }

    /** 只记录真实 enqueue 边界，不建立 Socket，也不伪造外部副作用已撤销。 */
    private class PendingCall(private val original: Request) : Call {
        @Volatile var enqueues = 0
        @Volatile private var cancelled = false
        val started = CompletableDeferred<Unit>()
        override fun request() = original
        override fun execute(): Response = error("合成测试不允许同步派发")
        override fun enqueue(responseCallback: Callback) { enqueues++; started.complete(Unit) }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = enqueues != 0
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = PendingCall(original)
    }

    @Test fun 已完成查权后删除或撤销许可仍在实际enqueue前阻止所有HTTP派发() = runBlocking {
        for (deleted in listOf(false, true)) {
            val created = CompletableDeferred<PendingCall>()
            val factory = Call.Factory { request -> PendingCall(request).also { created.complete(it) } }
            val client = McpHttpTransport(endpoint, McpCredentialSource { credential }, factory)
            val gate = Mutex(locked = true)
            val entered = CompletableDeferred<Unit>()
            var currentRequest: String? = "synthetic-request"
            var allowed = true
            val dispatch: suspend (() -> Unit) -> Unit = { send ->
                entered.complete(Unit)
                gate.withLock {
                    if (currentRequest == null || !allowed) throw McpTransportFailure("stale_configuration")
                    send()
                }
            }
            val pending = async {
                failure {
                    if (deleted) client.exchange("DELETE", dispatch = dispatch)
                    else client.exchangeRpc("""{"jsonrpc":"2.0","id":0,"method":"tools/list"}""", dispatch = dispatch)
                }
            }
            withTimeout(1000) { entered.await() }
            val call = created.await()
            if (deleted) currentRequest = null else allowed = false
            gate.unlock()
            assertEquals("stale_configuration", pending.await().code)
            assertEquals(0, call.enqueues); assertTrue(call.isCanceled())
        }
        assertEquals(0, server.requestCount); assertTrue(connected.isEmpty())
    }

    @Test fun 等待派发锁时取消即使锁用NonCancellable恢复也不能迟到enqueue() = runBlocking {
        val created = CompletableDeferred<PendingCall>()
        val client = McpHttpTransport(endpoint, McpCredentialSource { credential }, Call.Factory { request ->
            PendingCall(request).also { created.complete(it) }
        })
        val gate = Mutex(locked = true)
        val entered = CompletableDeferred<Unit>()
        val pending = launch {
            client.exchange("POST", "{}", dispatch = { send ->
                entered.complete(Unit)
                withContext(NonCancellable) { gate.withLock { send() } }
            })
        }
        withTimeout(1000) { entered.await() }
        val call = created.await()
        pending.cancel(); gate.unlock()
        withTimeout(1000) { pending.join() }
        assertEquals(0, call.enqueues); assertTrue(call.isCanceled())
        assertEquals(0, server.requestCount)
    }

    @Test fun 先派发再删除只取消在途等待且派发锁不覆盖网络等待() = runBlocking {
        val created = CompletableDeferred<PendingCall>()
        val client = McpHttpTransport(endpoint, McpCredentialSource { credential }, Call.Factory { request ->
            PendingCall(request).also { created.complete(it) }
        })
        val gate = Mutex()
        var available = true
        val pending = launch {
            client.exchange("POST", "{}", dispatch = { send ->
                gate.withLock { check(available); send() }
            })
        }
        val call = withTimeout(1000) { created.await().also { it.started.await() } }
        withTimeout(1000) { gate.withLock { available = false; pending.cancel() } }
        withTimeout(1000) { pending.join() }
        assertEquals(1, call.enqueues); assertTrue(call.isCanceled())
    }

    @Test fun 合成HTTPS使用已检查IP且保留原主机证书校验及不可重放正文() = runBlocking {
        server.enqueue(json("{\"ok\":true}").setHeader("Mcp-Session-Id", "synthetic-session"))
        val reply = transport().exchange("POST", "{\"method\":\"tools/call\"}", "previous-session", "2025-11-25")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method); assertEquals("/mcp", request.path)
        assertEquals("tools.example.com", request.getHeader("Host") ?: request.getHeader(":authority"))
        assertEquals("Bearer ${credential.value}", request.getHeader("Authorization"))
        assertEquals("previous-session", request.getHeader("Mcp-Session-Id"))
        assertEquals("2025-11-25", request.getHeader("MCP-Protocol-Version"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertEquals("{\"method\":\"tools/call\"}", request.body.readUtf8())
        assertEquals("{\"ok\":true}", reply.text); assertEquals("synthetic-session", reply.sessionId)
        assertEquals(1, lookups); assertEquals(publicAddress, connected.single().address); assertEquals(443, connected.single().port)
        assertFalse(reply.toString().contains("synthetic-session")); assertFalse(credential.toString().contains(credential.value))
    }

    @Test fun 私网或混合解析结果均在建立Socket之前阻断() = runBlocking {
        for (answers in listOf(listOf(InetAddress.getByName("127.0.0.1")), listOf(publicAddress, InetAddress.getByName("10.0.0.1")))) {
            failure { transport(resolver { answers }).exchange("POST", "{}") }
        }
        assertTrue(connected.isEmpty()); assertEquals(0, server.requestCount)
    }

    @Test fun 断开连接后DNS变成私网不会沿用上次检查或进行二次解析() = runBlocking {
        server.protocols = listOf(Protocol.HTTP_1_1)
        val client = transport(resolver { lookups++; listOf(if (lookups == 1) publicAddress else InetAddress.getByName("127.0.0.1")) })
        server.enqueue(json().setHeader("Connection", "close"))
        client.exchange("POST", "{}")
        failure { client.exchange("POST", "{}") }
        assertEquals(2, lookups); assertEquals(1, server.requestCount); assertEquals(1, connected.size)
    }

    @Test fun 重定向及认证错误均不跟随不转发秘密不自动认证() = runBlocking {
        val client = transport()
        for (status in listOf(301, 302, 307, 308, 401, 403, 404, 408, 421, 503)) {
            server.enqueue(json("{\"secret\":\"不得回显\"}").setResponseCode(status)
                .setHeader("Location", "https://other.example.com/leak").setHeader("WWW-Authenticate", "Bearer"))
            val before = server.requestCount
            failure { client.exchange("POST", "{}") }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun GET收到立即重试503也只能发送一次() = runBlocking {
        server.enqueue(json().setResponseCode(503).setHeader("Retry-After", "0"))
        val error = failure { transport().exchange("GET") }
        assertEquals("duplicate_request", error.code)
        assertEquals(1, server.requestCount)
    }

    @Test fun 原域名与证书不匹配时不发送HTTP且不会关闭主机验证() = runBlocking {
        failure { transport(target = McpEndpoint.parse("https://other.example.com/mcp")).exchange("POST", "{}") }
        assertEquals(0, server.requestCount)
        assertEquals(publicAddress, connected.single().address)
    }

    @Test fun 未受信任证书不能因为域名匹配而绕过证书链校验() = runBlocking {
        val client = McpHttpTransport.clientBuilder(endpoint, resolver { listOf(publicAddress) })
            .socketFactory(sockets).callTimeout(2, TimeUnit.SECONDS).build()
        failure { McpHttpTransport(endpoint, McpCredentialSource { credential }, client).exchange("POST", "{}") }
        assertEquals(0, server.requestCount)
    }

    @Test fun 声明长度及无声明的流式超限均按实际字节拒绝() = runBlocking {
        val client = transport()
        for (response in listOf(json("a".repeat(131073)), json().setChunkedBody("中".repeat(44000), 2048))) {
            server.enqueue(response)
            assertEquals("limit_exceeded", failure { client.exchange("POST", "{}") }.code)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun 非UTF8压缩与错误媒体类型不会进入上层解析器() = runBlocking {
        val client = transport()
        for (response in listOf(json().setBody(Buffer().writeByte(0xc3).writeByte(0x28)),
            json().setHeader("Content-Encoding", "gzip"), json().setHeader("Content-Type", "text/html"),
            json().setHeader("Content-Type", "application/json;charset=gbk"))) {
            server.enqueue(response)
            assertEquals("invalid_response", failure { client.exchange("POST", "{}") }.code)
        }
    }

    @Test fun 有界SSE只返回原始文本不自动重连或读取事件链接() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("retry: 1\nevent: message\ndata: {\"untrusted\":true}\n\n"))
        val reply = transport().exchange("POST", "{}")
        assertTrue(reply.eventStream); assertTrue(reply.text.contains("untrusted")); assertEquals(1, server.requestCount)
    }

    @Test fun 非法请求在读取凭据及网络之前被拒绝() = runBlocking {
        val client = transport()
        for (block in listOf<suspend () -> Unit>(
            { client.exchange("PUT", "{}") }, { client.exchange("GET", "{}") }, { client.exchange("POST") },
            { client.exchange("POST", "中".repeat(6000)) }, { client.exchange("POST", "{}", "bad\r\nheader") },
            { client.exchange("POST", "{}", protocolVersion = "future-version") })) {
            assertEquals("invalid_request", failure(block).code)
        }
        assertEquals(0, secretReads); assertEquals(0, lookups); assertEquals(0, server.requestCount)
    }

    @Test fun 挂起请求取消立即返回且不补发请求() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = transport()
        val pending = launch { client.exchange("POST", "{}") }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        withTimeout(1000) { pending.cancelAndJoin() }
        assertEquals(1, server.requestCount)
    }

    @Test fun 挂起响应按读取截止失败且不自动重试() = runBlocking {
        val client = transport(timeoutMillis = 4000)
        server.enqueue(json())
        client.exchange("POST", "{}")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        withTimeout(5000) { failure { client.exchange("POST", "{}") } }
        assertEquals(2, server.requestCount)
    }

    @Test fun 无密钥不加Authorization且服务Cookie不会带进下一次请求() = runBlocking {
        val client = transport(source = McpCredentialSource { null })
        server.enqueue(json().setHeader("Set-Cookie", "private=secret")); server.enqueue(json())
        repeat(2) { client.exchange("POST", "{}") }
        repeat(2) {
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
        }
    }

    @Test fun SDK传输返回匹配JSON且SSE进度不进入观察() = runBlocking {
        // setChunkedBody 生成 HTTP/1.1 分块帧，不能当成 HTTP/2 的 DATA 正文。
        server.protocols = listOf(Protocol.HTTP_1_1)
        val request = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"tools/list\"}"
        val response = "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"tools\":[]}}"
        val client = transport()
        server.enqueue(json(response))
        assertEquals(response, client.exchangeRpc(request).text)
        val events = "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\ndata: $response\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(events, 7))
        val reply = client.exchangeRpc(request)
        assertEquals(response, reply.text); assertFalse(reply.eventStream)
        assertEquals(2, server.requestCount)
    }

    @Test fun HTTP2的SSE正文不含HTTP1分块帧且匹配数字ID() = runBlocking {
        server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        val response = "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"tools\":[]}}"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $response\n\n"))
        val reply = transport().exchangeRpc("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"tools/list\"}")
        assertEquals(response, reply.text); assertFalse(reply.eventStream)
        assertEquals(1, server.requestCount)
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader(":authority"))
    }

    @Test fun RPC响应错ID和通知返回正文均不算成功且不重试() = runBlocking {
        val client = transport()
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}"))
        assertEquals("invalid_response", failure { client.exchangeRpc("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"tools/list\"}") }.code)
        for (response in listOf(json().setResponseCode(202), MockResponse().setResponseCode(204), json())) {
            server.enqueue(response)
            assertEquals("invalid_response", failure { client.exchangeRpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}") }.code)
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun RPC初始化通知接受202空正文但不执行额外请求() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))
        assertEquals("", transport().exchangeRpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").text)
        assertEquals(1, server.requestCount)
    }

    @Test fun 无效RPC或任意方法在读取手机密钥前拒绝() = runBlocking {
        val client = transport()
        for (body in listOf("{}", "[]", "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"resources/read\"}"))
            failure { client.exchangeRpc(body) }
        assertEquals(0, secretReads); assertEquals(0, server.requestCount)
    }
}
