package cn.yukehelper.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QrLoginApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: YuketangApi
    private val posts = AtomicInteger()
    private val lookups = AtomicInteger()
    private val loginRequests = AtomicInteger()
    private val socket = CompletableDeferred<WebSocket>()
    private var onConfirm: ((WebSocket) -> Unit)? = { confirm(it) }
    private var exchange: () -> MockResponse = { MockResponse().setHeader("Set-Cookie", "sessionid=private-session; Path=/; HttpOnly").setBody("{}") }
    private var user: () -> MockResponse = { MockResponse().setBody("""{"code":0,"data":{"id":123,"name":"测试"}}""") }

    private fun confirm(ws: WebSocket) { ws.send("""{"op":"loginsuccess","UserID":"000123","Auth":"private-auth"}""") }
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        api = YuketangApi(endpoint = { server.url("/") })
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/wsapp/" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        loginRequests.incrementAndGet(); socket.complete(webSocket); onConfirm?.invoke(webSocket)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                })
                "/pc/web_login" -> { posts.incrementAndGet(); exchange() }
                "/api/v3/user/basic-info" -> { lookups.incrementAndGet(); user() }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }
    @After fun cleanup() { server.shutdown() }
    private suspend fun result(): List<LoginState> = withTimeout(8_000) { api.qrLogin("pro.yuketang.cn").toList() }

    @Test fun transientUserLookupFailureRetriesOnlyTheGetWithTheSameSession() = runBlocking {
        user = { if (lookups.get() == 1) MockResponse().setResponseCode(503).setBody("private-server-error")
                 else MockResponse().setBody("""{"code":0,"data":{"id":123}}""") }
        val states = result()
        assertTrue(states.any { it == LoginState.Verifying })
        assertEquals(1, posts.get()); assertEquals(2, lookups.get())
        assertEquals(1, loginRequests.get())
        assertEquals("private-session", (states.last() as LoginState.Success).account.sessionId)
        val requests = List(server.requestCount) { server.takeRequest(1, TimeUnit.SECONDS)!! }
        val gets = requests.filter { it.path == "/api/v3/user/basic-info" }
        assertEquals(2, gets.size); assertTrue(gets.all { it.getHeader("Cookie") == "sessionid=private-session" })
        val post = requests.single { it.path == "/pc/web_login" }
        assertTrue(post.body.readUtf8().contains("\"UserID\":\"000123\""))
    }

    @Test fun duplicateConfirmationExchangesAndSavesOnlyOneAccount() = runBlocking {
        onConfirm = { confirm(it); confirm(it) }
        assertEquals(1, result().filterIsInstance<LoginState.Success>().size)
        assertEquals(1, posts.get()); assertEquals(1, lookups.get())
    }

    @Test fun serverClosingAfterConfirmationDoesNotCancelTheIndependentValidation() = runBlocking {
        onConfirm = { confirm(it); it.close(1000, "confirmed") }
        assertTrue(result().last() is LoginState.Success)
        assertEquals(1, posts.get())
    }

    @Test fun missingSessionIsReportedAtExchangeWithoutLeakingTheResponse() = runBlocking {
        exchange = { MockResponse().setBody("private-auth private-server-body") }
        try { result(); fail("Expected missing session") }
        catch (error: LoginFailure) {
            assertTrue(error.message!!.contains("会话交换")); assertFalse(error.message!!.contains("private"))
        }
        assertEquals(0, lookups.get())
    }

    @Test fun explicitSessionRejectionDoesNotRetryTheAuthOrUserLookup() = runBlocking {
        user = { MockResponse().setResponseCode(403).setBody("private-session") }
        try { result(); fail("Expected rejection") }
        catch (error: LoginFailure) {
            assertTrue(error.message!!.contains("账号验证")); assertFalse(error.message!!.contains("private"))
        }
        assertEquals(1, posts.get()); assertEquals(1, lookups.get())
    }

    @Test fun temporaryUserFailuresStopAfterThreeAttemptsWithSafeDiagnostics() = runBlocking {
        user = { MockResponse().setResponseCode(503).setBody("private-body") }
        try { result(); fail("Expected failure") }
        catch (error: LoginFailure) {
            assertTrue(error.message!!.contains("HTTP 503")); assertFalse(error.message!!.contains("private"))
        }
        assertEquals(1, posts.get()); assertEquals(3, lookups.get())
    }

    @Test fun invalidConfirmationDoesNotAttemptAnExchange() = runBlocking {
        onConfirm = { it.send("""{"op":"loginsuccess","UserID":123,"Auth":""}""") }
        try { result(); fail("Expected invalid confirmation") }
        catch (error: LoginFailure) { assertTrue(error.message!!.contains("登录消息")) }
        assertEquals(0, posts.get())
    }

    @Test fun cancellingTheLoginClosesItsActualSocket() = runBlocking {
        onConfirm = null
        val job = launch { api.qrLogin("pro.yuketang.cn").collect() }
        withTimeout(3_000) { socket.await() }
        job.cancelAndJoin()
        assertEquals(0, posts.get())
        assertEquals(1, loginRequests.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun waitingAcrossTwoOldRefreshIntervalsDoesNotReplaceTheTicket() = runTest {
        onConfirm = null
        val job = launch { api.qrLogin("pro.yuketang.cn").collect() }
        runCurrent()
        withContext(Dispatchers.IO) { withTimeout(3_000) { socket.await() } }
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, loginRequests.get())
        assertEquals(0, posts.get())
        job.cancelAndJoin()
    }
}
