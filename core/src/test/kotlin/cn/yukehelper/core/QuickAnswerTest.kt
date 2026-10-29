package cn.yukehelper.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickAnswerTest {
    private val account = Account("1", "本人", "pro.yuketang.cn", "test-session")
    private val access = LessonAccess(ActiveLesson(12, "7", "数学"), "test-token", "test-bearer")
    private fun root(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun start(id: String = "88", wait: Long = 0) = root(
        """{"op":"jumpinstart","jumpin":{"id":$id,"now":1700000000000,"start":${1700000000000 + wait}}}""")
    private val end = root("""{"op":"jumpinend"}""")
    private fun hello(id: String = "88") = root("""{"op":"hello","jumpinid":$id}""")
    private class Gateway : AutomationGateway {
        val posts = mutableListOf<Triple<String, LessonAccess, JsonPrimitive>>()
        var reads = 0
        var readWait = 0L
        var readCancelled = false
        var wait = 0L
        var postWait = 0L
        var error: Exception? = null
        var readError: Exception? = null
        var result = QuickAnswerResult.SUCCESS
        var blockPresentation = false
        var presentationStarted = false
        var question: Question? = null
        override suspend fun presentation(account: Account, access: LessonAccess, id: String): List<Question> {
            presentationStarted = true
            if (blockPresentation) awaitCancellation()
            return listOfNotNull(question)
        }
        override suspend fun submit(account: Account, access: LessonAccess, question: Question, answer: Answer, now: Long) = Unit
        override suspend fun danmu(account: Account, access: LessonAccess, content: String) = Unit
        override suspend fun bonus(account: Account, access: LessonAccess, id: String) = Unit
        override suspend fun quickAnswerInfo(account: Account, access: LessonAccess, id: JsonPrimitive): QuickAnswerRound {
            reads++
            try { delay(readWait) } catch (e: CancellationException) { readCancelled = true; throw e }
            readError?.let { throw it }
            return QuickAnswerRound(id, wait)
        }
        override suspend fun quickAnswer(account: Account, access: LessonAccess, id: JsonPrimitive): QuickAnswerResult {
            posts.add(Triple(account.key, access, id))
            delay(postWait)
            error?.let { throw it }
            return result
        }
    }
    private inner class Fixture(
        scope: CoroutineScope,
        val gateway: Gateway = Gateway(),
        ledger: QuickAnswerLedger = QuickAnswerLedger(),
        val user: Account = account,
    ) {
        var settings = CourseSettings(autoQuickAnswer = true)
        val events = mutableListOf<ClassroomEvent>()
        var aiStarted = false
        val engine = LessonAutomation(scope, user, access, gateway, AiAnswerer { _, _ -> aiStarted = true; awaitCancellation() },
            { settings }, { AiSettings(listOf(AiKey("test", AiProvider.QWEN, "test-key"))) }, events::add,
            now = { 123L }, quickAnswerLedger = ledger)
        fun receive(root: JsonObject) {
            Protocol.decodeFrame(root.toString(), access.lesson, user.name).filterIsInstance<LessonFrame.Signal>().forEach { engine.accept(it.root) }
        }
    }

    @Test fun protocolRoutesStartsAndEndsWithoutConfusingRollCallOrQuiz() {
        val frames = Protocol.decodeFrame(start().toString(), access.lesson, account.name)
        assertEquals(EventKind.QUICK_ANSWER, (frames.first() as LessonFrame.Event).event.kind)
        assertEquals(1, frames.filterIsInstance<LessonFrame.Signal>().size)
        assertEquals(1, Protocol.decodeFrame(end.toString(), access.lesson, account.name).filterIsInstance<LessonFrame.Signal>().size)
        assertTrue(Protocol.decodeFrame("""{"op":"callpaused","name":"其他人"}""", access.lesson, account.name).isEmpty())
    }
    @Test fun serverCountdownAndOriginalIdentifierArePreservedWithoutFloatingPointConversion() {
        val round = QuickAnswerProtocol.round(start("\"0001800552410780549125\"", 3000).obj("jumpin"))!!
        assertEquals(3000L, round.waitMillis)
        assertTrue(round.id.isString)
        assertEquals("0001800552410780549125", round.id.content)
        assertFalse(QuickAnswerProtocol.round(start("1800552410780549125").obj("jumpin"))!!.id.isString)
        assertEquals(0L, QuickAnswerProtocol.round(root("""{"id":88,"start":10,"now":20}"""))!!.waitMillis)
    }
    @Test fun invalidIdentifiersAndTimingDoNotCreateAnAutomaticAction() {
        for (text in listOf(
            """{"id":0,"start":1,"now":1}""", """{"id":-1,"start":1,"now":1}""",
            """{"id":true,"start":1,"now":1}""", """{"id":"test-secret","start":1,"now":1}""",
            """{"id":88,"start":1}""", """{"id":88,"start":300002,"now":1}""",
            """{"id":88,"start":9223372036854775807,"now":0}""", """{"id":88,"start":1,"now":-1}""",
        )) assertNull(text, QuickAnswerProtocol.round(root(text)))
    }
    @Test fun openRoundSubmitsImmediatelyAndReplayedMessagesNeverRepeatIt() = runTest {
        val f = Fixture(backgroundScope)
        repeat(5) { f.receive(start()) }; runCurrent()
        repeat(5) { f.receive(hello()) }; runCurrent()
        assertEquals(1, f.gateway.posts.size)
        assertEquals(0, f.gateway.reads)
        assertTrue(f.events.single().summary.contains("抢答成功"))
    }
    @Test fun countdownDoesNotUsePhoneClockAndDuplicatesDoNotRestartTheTimer() = runTest {
        val f = Fixture(backgroundScope)
        f.receive(start(wait = 3000)); runCurrent()
        advanceTimeBy(1000); f.receive(start(wait = 3000)); runCurrent()
        advanceTimeBy(1999); runCurrent(); assertTrue(f.gateway.posts.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(1, f.gateway.posts.size)
    }
    @Test fun disabledFeatureOrExcludedCourseDoesNotReadOrPost() = runTest {
        for (settings in listOf(CourseSettings(), CourseSettings(enabled = false, autoQuickAnswer = true))) {
            val f = Fixture(backgroundScope); f.settings = settings
            f.receive(start()); f.receive(hello()); runCurrent()
            assertTrue(f.gateway.posts.isEmpty()); assertEquals(0, f.gateway.reads)
        }
    }
    @Test fun turningOffBeforeCountdownFinishesPreventsSubmission() = runTest {
        val f = Fixture(backgroundScope)
        f.receive(start(wait = 3000)); runCurrent()
        f.settings = f.settings.copy(autoQuickAnswer = false)
        advanceTimeBy(3001); runCurrent(); assertTrue(f.gateway.posts.isEmpty())
    }
    @Test fun endCancelsCountdownAndReplayedStartCannotReviveTheRound() = runTest {
        val f = Fixture(backgroundScope)
        f.receive(start(wait = 3000)); runCurrent(); f.receive(end)
        f.receive(start()); f.receive(hello()); advanceTimeBy(4000); runCurrent()
        assertTrue(f.gateway.posts.isEmpty()); assertEquals(0, f.gateway.reads)
        assertTrue(f.events.isEmpty())
    }
    @Test fun nextRoundSupersedesOldCountdownWithoutCancellingItsOwnAttempt() = runTest {
        val f = Fixture(backgroundScope)
        f.receive(start("88", 3000)); runCurrent()
        f.receive(start("89")); runCurrent()
        advanceTimeBy(4000); runCurrent()
        assertEquals(listOf("89"), f.gateway.posts.map { it.third.content })
        f.receive(end); f.receive(start("90")); runCurrent()
        assertEquals(listOf("89", "90"), f.gateway.posts.map { it.third.content })
    }
    @Test fun slowPresentationFetchDoesNotBlockQuickAnswer() = runTest {
        val f = Fixture(backgroundScope); f.gateway.blockPresentation = true
        f.receive(root("""{"op":"presentationupdated","presentation":9}""")); runCurrent()
        assertTrue(f.gateway.presentationStarted)
        f.receive(start()); runCurrent()
        assertEquals(1, f.gateway.posts.size)
        assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun waitingForAiDoesNotBlockQuickAnswer() = runTest {
        val f = Fixture(backgroundScope)
        f.settings = f.settings.copy(single = AnswerMode.AI)
        f.gateway.question = Question("42", "43", "9", 1, listOf("A"), 1, "", 60.0, 1)
        f.receive(root("""{"op":"unlockproblem","problem":{"prob":42,"pres":9}}""")); runCurrent()
        assertTrue(f.aiStarted)
        f.receive(start()); runCurrent(); assertEquals(1, f.gateway.posts.size)
    }
    @Test fun reconnectReadsCurrentRoundBeforeSubmittingAndUsesRefreshedAccess() = runTest {
        val f = Fixture(backgroundScope); f.gateway.wait = 2000
        f.receive(hello("\"00088\"")); runCurrent()
        repeat(3) { f.receive(hello("\"00088\"")) }; runCurrent()
        assertEquals(1, f.gateway.reads); assertTrue(f.gateway.posts.isEmpty())
        val refreshed = LessonAccess(access.lesson, "test-new-token", "test-new-bearer")
        f.engine.updateAccess(refreshed)
        advanceTimeBy(2001); runCurrent()
        assertSame(refreshed, f.gateway.posts.single().second)
        assertEquals(JsonPrimitive("00088"), f.gateway.posts.single().third)
    }
    @Test fun endingDuringRecoveryCancelsReadAndPreventsLatePost() = runTest {
        val f = Fixture(backgroundScope); f.gateway.readWait = 2000
        f.receive(hello()); runCurrent(); f.receive(end); runCurrent()
        advanceTimeBy(3000); runCurrent()
        assertTrue(f.gateway.readCancelled); assertTrue(f.gateway.posts.isEmpty())
    }
    @Test fun recoveryFailureDoesNotFallBackToBlindParticipationOrExposeErrors() = runTest {
        val f = Fixture(backgroundScope); f.gateway.readError = NetworkFailure("test-private-session")
        f.receive(hello()); runCurrent(); f.receive(hello()); runCurrent()
        assertEquals(1, f.gateway.reads); assertTrue(f.gateway.posts.isEmpty())
        assertTrue(f.events.single().summary.contains("未自动参与"))
        assertFalse(f.events.single().summary.contains("test-private-session"))
    }
    @Test fun uncertainTimeoutNeverReportsSuccessOrResubmits() = runTest {
        val f = Fixture(backgroundScope); f.gateway.postWait = 10_000
        f.receive(start()); runCurrent(); advanceTimeBy(3001); runCurrent()
        repeat(4) { f.receive(start()); f.receive(hello()) }; runCurrent()
        assertEquals(1, f.gateway.posts.size)
        assertTrue(f.events.single().summary.contains("结果未确认"))
        assertFalse(f.events.single().summary.contains("抢答成功"))
    }
    @Test fun rejectedAttemptKeepsOnlySafeNumericDiagnosticsAndDoesNotRetry() = runTest {
        val f = Fixture(backgroundScope); f.gateway.error = ActionFailure(400, 50400)
        f.receive(start()); runCurrent(); f.receive(start()); runCurrent()
        assertEquals(1, f.gateway.posts.size)
        assertTrue(f.events.single().summary.contains("HTTP 400，接口代码 50400"))
        assertFalse(f.events.single().summary.contains("抢答成功"))
    }
    @Test fun endedOrMissedResponsesDoNotReportSuccess() = runTest {
        for (result in listOf(QuickAnswerResult.ENDED, QuickAnswerResult.MISSED)) {
            val f = Fixture(backgroundScope); f.gateway.result = result
            f.receive(start()); runCurrent()
            assertTrue(f.events.single().summary.contains("未抢到"))
            assertFalse(f.events.single().summary.contains("抢答成功"))
        }
    }
    @Test fun cancellingTheMonitoringScopeCancelsCountdown() = runTest {
        val owner = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val f = Fixture(owner); f.receive(start(wait = 3000)); runCurrent()
        owner.cancel(); advanceTimeBy(4000); runCurrent()
        assertTrue(f.gateway.posts.isEmpty()); assertTrue(f.events.isEmpty())
    }
    @Test fun applicationLedgerPreventsDuplicatesAfterSettingsRestartAndIsolatesAccounts() = runTest {
        val ledger = QuickAnswerLedger()
        val first = Fixture(backgroundScope, ledger = ledger)
        first.receive(start()); runCurrent()
        val restarted = Fixture(backgroundScope, ledger = ledger)
        restarted.receive(hello()); restarted.receive(start()); runCurrent()
        assertTrue(restarted.gateway.posts.isEmpty()); assertEquals(0, restarted.gateway.reads)
        val other = Fixture(backgroundScope, ledger = ledger, user = Account("2", "乙", "pro.yuketang.cn", "test-other"))
        other.receive(start()); runCurrent(); assertEquals(1, other.gateway.posts.size)
    }
    @Test fun cancelledCountdownCanBeRecoveredByTheNewMonitorWithoutConsumingAnAttempt() = runTest {
        val ledger = QuickAnswerLedger()
        val owner = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val f = Fixture(owner, ledger = ledger); f.receive(start(wait = 3000)); runCurrent()
        owner.cancel(); runCurrent()
        val restarted = Fixture(backgroundScope, ledger = ledger)
        restarted.receive(hello()); runCurrent()
        assertEquals(1, restarted.gateway.posts.size); assertTrue(f.gateway.posts.isEmpty())
    }
    @Test fun quickAnswerIsGlobalAndOldConfigurationKeepsItDisabled() {
        val second = Account("2", "乙", "www.yuketang.cn", "test-other")
        val config = Configuration(listOf(AccountProfile(account, excludedCourses = setOf("8")), AccountProfile(second)), account.key)
            .withCourseSettings(CourseSettings(autoQuickAnswer = true, voice = Subscription(true, setOf(EventKind.QUICK_ANSWER))))
        val decoded = ConfigurationCodec.decode(ConfigurationCodec.encode(config))
        assertTrue(decoded.effectiveCourseSettings(account.key, "7").autoQuickAnswer)
        assertTrue(decoded.effectiveCourseSettings(second.key, "future").autoQuickAnswer)
        assertFalse(decoded.effectiveCourseSettings(account.key, "8").enabled)
        assertTrue(decoded.courseSettings.voice.accepts(EventKind.QUICK_ANSWER))
        val old = root(ConfigurationCodec.encode(config))
        val withoutFlag = JsonObject(old + ("courseSettings" to JsonObject(old.obj("courseSettings") - "quickAnswer")))
        val restored = ConfigurationCodec.decode(withoutFlag.toString())
        assertFalse(restored.courseSettings.autoQuickAnswer)
        assertEquals(config.ai.keys.size, restored.ai.keys.size)
        assertEquals(config.profiles.map { it.excludedCourses }, restored.profiles.map { it.excludedCourses })
    }
}
