package cn.yukehelper.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationTest {
    private val account = Account("1", "本人", "pro.yuketang.cn", "test")
    private val access = LessonAccess(ActiveLesson(12, "7", "数学"), "token", null)
    private fun root(text: String) = Json.parseToJsonElement(text).jsonObject
    private val unlock = root("""{"op":"unlockproblem","problem":{"prob":42,"sid":43,"pres":9,"limit":60}}""")
    private fun question(type: Int = 1) = Question("42", "43", "9", type, listOf("A", "B", "C"), if (type == 1) 1 else 2, "https://example.com/slide.jpg", 60.0, 100_000)
    private class Gateway(var question: Question) : AutomationGateway {
        var posts = 0
        var failPosts = 0
        var readsFail = false
        var uncertainAccepted = false
        var uncertainAcceptedOnPost: Int? = null
        var requireStringId = false
        var postFailure: Exception? = null
        var failReadbackAfterThird = false
        val submittedIds = mutableListOf<JsonPrimitive>()
        var answer: Answer? = null
        var danmus = 0
        var bonuses = 0
        override suspend fun presentation(account: Account, access: LessonAccess, id: String): List<Question> {
            if (readsFail) throw NetworkFailure("offline")
            return listOf(question)
        }
        override suspend fun submit(account: Account, access: LessonAccess, question: Question, answer: Answer, now: Long) {
            posts++
            submittedIds.add(QuestionProtocol.answer(question, answer, now)["problemId"]!!.jsonPrimitive)
            if (failReadbackAfterThird && posts == 3) readsFail = true
            if (requireStringId && !submittedIds.last().isString) throw ActionFailure(400, 50400, LessonEntryDetail.STRING_TYPE)
            postFailure?.let { throw it }
            if (uncertainAccepted || posts == uncertainAcceptedOnPost) { this.question = question.copy(answered = true); throw NetworkFailure("lost response") }
            if (posts <= failPosts) throw NetworkFailure("failed")
            this.answer = answer
            this.question = question.copy(answered = true)
        }
        override suspend fun danmu(account: Account, access: LessonAccess, content: String) { danmus++ }
        override suspend fun bonus(account: Account, access: LessonAccess, id: String) { bonuses++ }
        override suspend fun quickAnswerInfo(account: Account, access: LessonAccess, id: JsonPrimitive) = error("unexpected quick-answer read")
        override suspend fun quickAnswer(account: Account, access: LessonAccess, id: JsonPrimitive) = error("unexpected quick-answer post")
    }
    private inner class Fixture(scope: TestScope, q: Question = question()) {
        val gateway = Gateway(q)
        var settings = CourseSettings(single = AnswerMode.RANDOM)
        var keys = AiSettings()
        var provider: AiAnswerer = AiAnswerer { _, _ -> Answer.Choices(listOf("B")) }
        val events = mutableListOf<ClassroomEvent>()
        val engine = LessonAutomation(scope.backgroundScope, account, access, gateway,
            AiAnswerer { q, k -> provider.answer(q, k) }, { settings }, { keys }, { events.add(it) },
            { 100_000 + scope.testScheduler.currentTime }, Random(5))
    }
    @Test fun repeatedUnlockAndReconnectSlideAliasSubmitOnlyOnce() = runTest {
        val f = Fixture(this)
        f.engine.accept(unlock); runCurrent()
        repeat(3) { f.engine.accept(unlock) }
        f.engine.accept(root("""{"op":"hello","presentation":9,"unlockedproblem":[43]}""")); runCurrent()
        assertEquals(1, f.gateway.posts)
    }
    @Test fun reconnectRecoversAQuestionWithNoUnlockFrame() = runTest {
        val f = Fixture(this)
        f.engine.accept(root("""{"op":"hello","timeline":[{"type":"slide","pres":9}],"unlockedproblem":[43]}""")); runCurrent()
        assertEquals(1, f.gateway.posts)
    }
    @Test fun preexistingManualAnswerIsNeverOverwritten() = runTest {
        val f = Fixture(this, question().copy(answered = true))
        f.engine.accept(unlock); runCurrent()
        assertEquals(0, f.gateway.posts)
    }
    @Test fun manualAnswerDuringNearDeadlineHoldIsPreserved() = runTest {
        val f = Fixture(this); f.settings = f.settings.copy(nearDeadline = true)
        f.engine.accept(unlock); runCurrent()
        assertEquals(0, f.gateway.posts)
        advanceTimeBy(10_000); f.gateway.question = f.gateway.question.copy(answered = true)
        advanceTimeBy(45_000); runCurrent()
        assertEquals(0, f.gateway.posts)
        assertTrue(f.events.any { "保留" in it.summary })
    }
    @Test fun nearDeadlineRandomAnswerWaitsUntilTenToFifteenSecondsBeforeClose() = runTest {
        val f = Fixture(this); f.settings = f.settings.copy(nearDeadline = true)
        f.engine.accept(unlock); runCurrent(); advanceTimeBy(44_999); runCurrent()
        assertEquals(0, f.gateway.posts)
        advanceTimeBy(5_002); runCurrent()
        assertEquals(1, f.gateway.posts)
    }
    @Test fun disablingCourseWhileWaitingPreventsTheWrite() = runTest {
        val f = Fixture(this); f.settings = f.settings.copy(nearDeadline = true)
        f.engine.accept(unlock); runCurrent(); f.settings = f.settings.copy(enabled = false)
        advanceTimeBy(55_000); runCurrent()
        assertEquals(0, f.gateway.posts)
    }
    @Test fun cancellingTheOwnerCancelsAiAndPreventsSubmission() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val g = Gateway(question())
        var cancelled = false
        val engine = LessonAutomation(owner, account, access, g, AiAnswerer { _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        }, { CourseSettings(single = AnswerMode.AI) }, { AiSettings(listOf(AiKey("test", AiProvider.QWEN, "test"))) }, {}, { 100_000 })
        engine.accept(unlock); runCurrent(); owner.cancel(); runCurrent()
        assertTrue(cancelled); assertEquals(0, g.posts)
    }
    @Test fun failedFirstAiKeyFallsBackToSecondKey() = runTest {
        val f = Fixture(this); val seen = mutableListOf<String>()
        f.settings = f.settings.copy(single = AnswerMode.AI)
        f.keys = AiSettings(listOf(AiKey("first", AiProvider.QWEN, "test1"), AiKey("second", AiProvider.GEMINI, "test2")))
        f.provider = AiAnswerer { _, key -> seen.add(key.name); if (key.name == "first") error("invalid") else Answer.Choices(listOf("C")) }
        f.engine.accept(unlock); runCurrent()
        assertEquals(listOf("first", "second"), seen)
        assertEquals(Answer.Choices(listOf("C")), f.gateway.answer)
    }
    @Test fun exhaustedAiBudgetCancelsProviderAndUsesRandomFallback() = runTest {
        val f = Fixture(this); var cancelled = false
        f.settings = f.settings.copy(single = AnswerMode.AI)
        f.keys = AiSettings(listOf(AiKey("test", AiProvider.QWEN, "test")))
        f.provider = AiAnswerer { _, _ -> try { awaitCancellation() } finally { cancelled = true } }
        f.engine.accept(unlock); runCurrent(); advanceTimeBy(20_001); runCurrent()
        assertTrue(cancelled); assertEquals(1, f.gateway.posts)
        assertTrue(f.events.any { "回退" in it.summary })
    }
    @Test fun deepseekFailureUsesNextProviderAndDoesNotLeakItsError() = runTest {
        val f = Fixture(this); val seen = mutableListOf<AiProvider>()
        f.settings = f.settings.copy(single = AnswerMode.AI)
        f.keys = AiSettings(listOf(AiKey("DS", AiProvider.DEEPSEEK, "test-deepseek"), AiKey("Q", AiProvider.QWEN, "test-qwen")))
        f.provider = AiAnswerer { _, key ->
            seen.add(key.provider)
            if (key.provider == AiProvider.DEEPSEEK) throw NetworkFailure("test-deepseek provider response")
            Answer.Choices(listOf("B"))
        }
        f.engine.accept(unlock); runCurrent()
        assertEquals(listOf(AiProvider.DEEPSEEK, AiProvider.QWEN), seen)
        assertEquals(Answer.Choices(listOf("B")), f.gateway.answer)
        assertTrue(f.events.none { "test-deepseek" in it.summary })
    }
    @Test fun noKeysShortAnswerFallsBackToSingleSpace() = runTest {
        val f = Fixture(this, question(5)); f.settings = f.settings.copy(short = AnswerMode.AI)
        f.engine.accept(unlock); runCurrent()
        assertEquals(Answer.Text(" "), f.gateway.answer)
    }
    @Test fun unknownDeadlineDoesNotHoldRandomAnswers() = runTest {
        val f = Fixture(this, question().copy(sentAt = 0, limitSeconds = 0.0)); f.settings = f.settings.copy(nearDeadline = true)
        f.engine.accept(root("""{"op":"hello","presentation":9,"unlockedproblem":[42]}""")); runCurrent()
        assertEquals(1, f.gateway.posts)
    }
    @Test fun expiredQuestionDoesNotSubmitEvenIfAReplayedFrameHasNewLimit() = runTest {
        val f = Fixture(this, question().copy(sentAt = 1000))
        f.engine.accept(unlock); runCurrent()
        assertEquals(0, f.gateway.posts)
    }
    @Test fun failedStatusRefreshDefersSubmissionUntilRecovery() = runTest {
        val f = Fixture(this); f.settings = f.settings.copy(nearDeadline = true)
        f.engine.accept(unlock); runCurrent(); f.gateway.readsFail = true
        advanceTimeBy(50_001); runCurrent(); assertEquals(0, f.gateway.posts)
        f.gateway.readsFail = false; f.engine.accept(unlock); runCurrent()
        assertEquals(1, f.gateway.posts)
    }
    @Test fun uncertainAcceptedSubmissionIsRefreshedBeforeRetry() = runTest {
        val f = Fixture(this); f.gateway.uncertainAccepted = true
        f.engine.accept(unlock); runCurrent(); advanceTimeBy(351); runCurrent()
        assertEquals(1, f.gateway.posts)
        assertTrue(f.events.any { "保留" in it.summary })
    }
    @Test fun failedSubmissionsAreLimitedToThreeAcrossReplayedFrames() = runTest {
        val f = Fixture(this); f.gateway.failPosts = 100
        f.engine.accept(unlock); runCurrent(); advanceTimeBy(1000); runCurrent()
        repeat(5) { f.engine.accept(unlock) }; runCurrent()
        assertEquals(3, f.gateway.posts)
    }
    @Test fun acceptedThirdSubmissionIsReadBackBeforeReportingFailure() = runTest {
        val f = Fixture(this)
        f.gateway.failPosts = 2
        f.gateway.uncertainAcceptedOnPost = 3
        f.engine.accept(unlock); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(3, f.gateway.posts)
        assertTrue(f.gateway.question.answered)
        assertTrue(f.events.any { "已有答案" in it.summary || "已确认" in it.summary })
        assertTrue(f.events.none { "达到 3 次" in it.summary })
        repeat(3) { f.engine.accept(unlock) }; runCurrent()
        assertEquals(3, f.gateway.posts)
    }
    @Test fun reportedStringParameterRejectionUsesTheSameIdWithinTheExistingAttemptLimit() = runTest {
        val id = "1800552410780549125"
        val f = Fixture(this, question().copy(id = id))
        f.gateway.requireStringId = true
        f.engine.accept(root("""{"op":"unlockproblem","problem":{"prob":"$id","pres":9}}"""))
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(2, f.gateway.posts)
        assertEquals(listOf(false, true), f.gateway.submittedIds.map { it.isString })
        assertTrue(f.gateway.submittedIds.all { it.content == id })
        assertTrue(f.events.any { "已提交" in it.summary })
    }
    @Test fun otherRejectionsNeverChangeTheIdentifierOrExposeExceptionText() = runTest {
        val errors = listOf(
            ActionFailure(400, 50000),
            ActionFailure(400, 50400, LessonEntryDetail.CSRF),
            ActionFailure(400, 50400, LessonEntryDetail.NUMBER_TYPE),
            ActionFailure(400, 50400, LessonEntryDetail.REQUIRED),
            ActionFailure(400, 50400, LessonEntryDetail.JSON),
            NetworkFailure("test-secret-session", ConnectionIssue.TIMEOUT),
        )
        for (error in errors) {
            val f = Fixture(this); f.gateway.postFailure = error
            f.engine.accept(unlock); runCurrent(); advanceTimeBy(1000); runCurrent()
            assertEquals(3, f.gateway.posts)
            assertTrue(f.gateway.submittedIds.none { it.isString })
            val final = f.events.last().summary
            assertTrue(final.contains("提交未确认"))
            assertFalse(final.contains("test-secret-session"))
            if (error is ActionFailure) {
                assertTrue(final.contains("HTTP ${error.httpStatus}"))
                assertTrue(final.contains("接口代码 ${error.code}"))
            }
        }
    }
    @Test fun failedFinalReadbackKeepsStatusUnconfirmedAndNeverAddsAFourthSubmission() = runTest {
        val f = Fixture(this)
        f.gateway.failPosts = 2
        f.gateway.uncertainAcceptedOnPost = 3
        f.gateway.failReadbackAfterThird = true
        f.engine.accept(unlock); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(3, f.gateway.posts)
        assertTrue(f.events.any { "提交未确认" in it.summary })
        assertTrue(f.events.none { "已确认" in it.summary })
        f.gateway.readsFail = false
        repeat(4) { f.engine.accept(unlock) }; runCurrent()
        assertEquals(3, f.gateway.posts)
    }
    @Test fun autoDanmuAndBonusAreDeduplicated() = runTest {
        val f = Fixture(this); f.settings = f.settings.copy(autoDanmu = true, autoBonus = true)
        repeat(6) { f.engine.accept(root("""{"op":"newdanmu","danmu":" 好的 "}""")) }
        repeat(3) { f.engine.accept(root("""{"op":"gainbonus","redpacket":{"redEnvelopeId":88}}""")) }
        runCurrent(); assertEquals(1, f.gateway.danmus); assertEquals(1, f.gateway.bonuses)
    }
    @Test fun newAutomaticFeaturesDefaultToOff() = runTest {
        val f = Fixture(this); f.settings = CourseSettings()
        f.engine.accept(unlock)
        repeat(4) { f.engine.accept(root("""{"op":"newdanmu","danmu":"你好"}""")) }
        f.engine.accept(root("""{"op":"gainbonus","redEnvelopeId":88}""")); runCurrent()
        assertEquals(0, f.gateway.posts + f.gateway.danmus + f.gateway.bonuses)
    }
    @Test fun rollingDanmuWindowExpiresAndNormalizesText() {
        val window = DanmuWindow()
        assertFalse(window.receive(" HELLO ", 0, 3))
        assertFalse(window.receive("hello", 100, 3))
        assertTrue(window.receive("Hello", 200, 3))
        assertFalse(window.receive("hello", 300, 3))
        assertFalse(window.receive("hello", 60_500, 3))
        assertFalse(window.receive("hello", 60_600, 3))
        assertTrue(window.receive("hello", 60_700, 3))
    }
    @Test fun votingRandomAnswersNeverExceedTheServerLimit() = runTest {
        val f = Fixture(this, question(3).copy(maxChoices = 1)); f.settings = f.settings.copy(vote = AnswerMode.RANDOM)
        f.engine.accept(unlock); runCurrent()
        val selected = (f.gateway.answer as Answer.Choices).options
        assertEquals(1, selected.size); assertTrue(selected.all { it in f.gateway.question.options })
    }
    @Test fun serverClockOffsetPreventsLateSubmissions() = runTest {
        val f = Fixture(this)
        f.engine.accept(root("""{"op":"unlockproblem","dt":161000,"problem":{"prob":42,"pres":9,"limit":60}}""")); runCurrent()
        assertEquals(0, f.gateway.posts)
    }
    @Test fun concurrentAccountsShareAiAndReadUpdatesForNewQuestionsWithoutRecreatingAutomations() = runTest {
        val second = Account("2", "另一同学", "www.yuketang.cn", "test-second")
        val accounts = listOf(account, second)
        var config = Configuration(accounts.map { AccountProfile(it) }, account.key,
            courseSettings = CourseSettings(single = AnswerMode.AI),
            ai = AiSettings(listOf(AiKey("共用", AiProvider.DEEPSEEK, "test-original"))))
        val observed = mutableListOf<Pair<String, String>>()
        val gateways = accounts.map { Gateway(question()) }
        val engines = accounts.mapIndexed { i, user ->
            LessonAutomation(backgroundScope, user, access, gateways[i],
                AiAnswerer { _, key -> observed.add(user.key to key.secret); Answer.Choices(listOf("B")) },
                { config.effectiveCourseSettings(user.key, access.lesson.classroomId) }, { config.ai }, {}, { 100_000 })
        }
        engines.forEach { it.accept(unlock) }; runCurrent()
        assertEquals(accounts.map { it.key to "test-original" }.toSet(), observed.toSet())
        assertTrue(gateways.all { it.posts == 1 && it.answer == Answer.Choices(listOf("B")) })

        config = config.copy(selected = second.key).withAiSettings(AiSettings(listOf(AiKey("更新", AiProvider.DEEPSEEK, "test-updated"))))
        gateways.forEach { it.question = question().copy(id = "44", slideId = "45") }
        engines.forEach { it.accept(root("""{"op":"unlockproblem","problem":{"prob":44,"sid":45,"pres":9,"limit":60}}""")) }
        runCurrent()
        assertEquals(4, observed.size)
        assertEquals(accounts.map { it.key to "test-updated" }.toSet(), observed.takeLast(2).toSet())
        assertTrue(gateways.all { it.posts == 2 })
    }
}
