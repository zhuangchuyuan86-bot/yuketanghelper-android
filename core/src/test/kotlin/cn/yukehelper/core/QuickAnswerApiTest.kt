package cn.yukehelper.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QuickAnswerApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: YuketangApi
    private val account = Account("1", "本人", "pro.yuketang.cn", "test-session")
    private val access = LessonAccess(ActiveLesson(12, "7", "数学"), "test-token", "test-bearer")
    @Before fun setup() { server = MockWebServer().apply { start() }; api = YuketangApi(endpoint = { server.url("/") }) }
    @After fun close() { server.shutdown() }

    @Test fun participationUsesExactEndpointAndOriginalIdWithAccountCredentials() = runBlocking {
        for (id in listOf(JsonPrimitive(1800552410780549125L), JsonPrimitive("0001800552410780549125"))) {
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"status":1}}"""))
            assertEquals(QuickAnswerResult.SUCCESS, api.quickAnswer(account, access, id))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v3/lesson/rapid-answer/participate", request.path)
            assertEquals(buildJsonObject { put("id", id) }, Json.parseToJsonElement(request.body.readUtf8()))
            assertEquals("sessionid=test-session", request.getHeader("Cookie"))
            assertEquals("Bearer test-bearer", request.getHeader("Authorization"))
        }
    }
    @Test fun recoveryUsesGetInfoAndServerCountdown() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":"00088","now":1700000000000,"start":1700000003000}}"""))
        val info = api.quickAnswerInfo(account, access, JsonPrimitive("00088"))
        assertEquals(QuickAnswerRound(JsonPrimitive("00088"), 3000), info)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v3/lesson/rapid-answer/get-info?id=00088", request.path)
        assertEquals("Bearer test-bearer", request.getHeader("Authorization"))
        assertEquals("sessionid=test-session", request.getHeader("Cookie"))
    }
    @Test fun mismatchedRecoveryCannotParticipateInADifferentRound() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":89,"now":1000,"start":1000}}"""))
        try { api.quickAnswerInfo(account, access, JsonPrimitive(88)); fail() }
        catch (_: NetworkFailure) { }
    }
    @Test fun actualStatusRatherThanHttpSuccessDeterminesTheOutcome() = runBlocking {
        for ((status, expected) in listOf(1 to QuickAnswerResult.SUCCESS, 2 to QuickAnswerResult.ENDED,
            0 to QuickAnswerResult.MISSED, 99 to QuickAnswerResult.MISSED)) {
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"status":$status}}"""))
            assertEquals(expected, api.quickAnswer(account, access, JsonPrimitive(88)))
        }
    }
    @Test fun incompleteOrRejectedResponseNeverCountsAsSuccess() = runBlocking {
        for (body in listOf("""{"code":0,"data":{}}""", """{"code":0,"data":{"status":null}}""",
            """{"success":true,"data":{"status":1}}""", """{"code":0,"success":false,"data":{"status":1}}""")) {
            server.enqueue(MockResponse().setBody(body))
            var failed = false
            try { api.quickAnswer(account, access, JsonPrimitive(88)) }
            catch (_: ActionFailure) { failed = true }
            catch (_: NetworkFailure) { failed = true }
            assertTrue(body, failed)
        }
    }
    @Test fun httpRejectionOnlyRetainsNumericDiagnostics() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":50400,"msg":"test-private-session","data":{"status":1}}"""))
        try { api.quickAnswer(account, access, JsonPrimitive(88)); fail() }
        catch (e: ActionFailure) {
            assertEquals(400, e.httpStatus); assertEquals(50400, e.code)
            assertFalse(e.toString().contains("test-private-session"))
        }
    }
    @Test fun serviceUnavailableDoesNotTriggerAnAutomaticTransportRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("""{"code":503}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"status":1}}"""))
        try { api.quickAnswer(account, access, JsonPrimitive(88)); fail() }
        catch (_: ActionFailure) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun redirectsDoNotRepeatParticipationAtAnotherAddress() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/unexpected")))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"status":1}}"""))
        try { api.quickAnswer(account, access, JsonPrimitive(88)); fail() }
        catch (_: ActionFailure) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun expiredSessionIsPropagatedToTheAccountMonitor() = runBlocking {
        for (response in listOf(MockResponse().setResponseCode(401),
            MockResponse().setBody("""{"code":-1,"msg":"登录过期"}"""))) {
            server.enqueue(response)
            try { api.quickAnswer(account, access, JsonPrimitive(88)); fail() }
            catch (_: SessionExpired) { }
        }
    }
    @Test fun realWebSocketStartFlowsThroughAutomationToExactlyOneHttpParticipation() = runBlocking {
        val received = CompletableDeferred<ClassroomEvent>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/wsapp/" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"op":"hello"}""")
                        repeat(3) { webSocket.send("""{"op":"jumpinstart","jumpin":{"id":"00088","now":1000,"start":1000}}""") }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                })
                "/api/v3/lesson/rapid-answer/participate" -> MockResponse().setBody("""{"code":0,"data":{"status":1}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val job = launch {
            val automation = LessonAutomation(this, account, access, api, AiAnswerer { _, _ -> error("unused") },
                { CourseSettings(autoQuickAnswer = true) }, { AiSettings() }, { received.complete(it) })
            api.frames(account, access).collect { if (it is LessonFrame.Signal) automation.accept(it.root) }
        }
        try {
            val result = withTimeout(5000) { received.await() }
            assertEquals(EventKind.QUICK_ANSWER, result.kind)
            assertTrue(result.summary.contains("抢答成功"))
        } finally { job.cancelAndJoin() }
        assertEquals(2, server.requestCount)
        assertEquals("/wsapp/", server.takeRequest().path)
        val post = server.takeRequest()
        assertEquals("/api/v3/lesson/rapid-answer/participate", post.path)
        assertEquals(JsonPrimitive("00088"), Json.parseToJsonElement(post.body.readUtf8()).jsonObject["id"])
    }
}
