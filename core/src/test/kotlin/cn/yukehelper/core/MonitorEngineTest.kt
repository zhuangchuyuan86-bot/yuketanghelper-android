package cn.yukehelper.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorEngineTest {
    private val account = Account("1", "同学", "pro.yuketang.cn", "test")
    private class FakeGateway : ClassroomGateway {
        var active = listOf(ActiveLesson(12, "34", "课程"))
        var checkIns = 0
        var closed = 0
        var finishImmediately = false
        override suspend fun activeLessons(account: Account) = active
        override suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess {
            checkIns++; return LessonAccess(lesson, "token", null)
        }
        override fun frames(account: Account, access: LessonAccess) = flow {
            try {
                emit(LessonFrame.Connected)
                if (finishImmediately) emit(LessonFrame.Finished) else awaitCancellation()
            } finally { closed++ }
        }
    }
    private class ReconnectingGateway(private val stream: suspend FlowCollector<LessonFrame>.(Int) -> Unit) : ClassroomGateway {
        var connections = 0
        var checkIns = 0
        val tokens = mutableListOf<String>()
        override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(12, "34", "课程"))
        override suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess {
            checkIns++
            return LessonAccess(lesson, "token-$checkIns", null)
        }
        override fun frames(account: Account, access: LessonAccess) = flow {
            tokens.add(access.lessonToken)
            stream(++connections)
        }
    }
    @Test fun rejectedEntryIsImmediateSpecificAndRetriesOnlyOncePerMinute() = runTest {
        val updates = mutableListOf<MonitorUpdate>()
        var entries = 0
        val gateway = object : ClassroomGateway {
            override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(12, "34", "课程"))
            override suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess {
                entries++
                throw LessonEntryFailure(LessonEntryIssue.REJECTED, 400, 98765)
            }
            override fun frames(account: Account, access: LessonAccess) = flow<LessonFrame> { error("Must not open socket") }
        }
        val job = backgroundScope.launch { MonitorEngine(gateway, 1_000, elapsed = { testScheduler.currentTime })
            .run(account, updates::add) }
        runCurrent()
        val error = updates.filterIsInstance<MonitorUpdate.Event>().single().event
        assertEquals(EventKind.ERROR, error.kind)
        assertTrue(error.summary.contains("HTTP 400"))
        assertTrue(error.summary.contains("98765"))
        assertFalse(error.summary.contains("消息连接持续中断"))
        advanceTimeBy(59_999); runCurrent(); assertEquals(1, entries)
        advanceTimeBy(1); runCurrent(); assertEquals(2, entries)
        assertEquals(1, updates.filterIsInstance<MonitorUpdate.Event>().size)
        advanceTimeBy(60_000); runCurrent(); assertEquals(3, entries)
        assertEquals(2, updates.filterIsInstance<MonitorUpdate.Event>().size)
        assertFalse(updates.filterIsInstance<MonitorUpdate.Connection>().any { it.connected })
        job.cancelAndJoin()
        advanceTimeBy(120_000); runCurrent(); assertEquals(3, entries)
    }
    @Test fun successfulEntryRetryRecoversAfterMessagesArriveWithoutFalseSignIn() = runTest {
        val updates = mutableListOf<MonitorUpdate>()
        var entries = 0
        val gateway = object : ClassroomGateway {
            override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(12, "34", "课程"))
            override suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess {
                if (++entries == 1) throw LessonEntryFailure(LessonEntryIssue.IDENTITY, 400, 50019)
                return LessonAccess(lesson, "token", null)
            }
            override fun frames(account: Account, access: LessonAccess) = flow {
                emit(LessonFrame.Connected)
                emit(LessonFrame.Event(ClassroomEvent(EventKind.QUESTION, "题目", "课程", 12)))
                awaitCancellation()
            }
        }
        val job = backgroundScope.launch { MonitorEngine(gateway, 1_000, elapsed = { testScheduler.currentTime })
            .run(account, updates::add) }
        runCurrent()
        assertFalse(updates.filterIsInstance<MonitorUpdate.Event>().any { it.event.kind == EventKind.SIGN_IN })
        assertTrue(updates.filterIsInstance<MonitorUpdate.Event>().single().event.summary.contains("绑定学校身份"))
        advanceTimeBy(60_000); runCurrent()
        val events = updates.filterIsInstance<MonitorUpdate.Event>().map { it.event }
        assertEquals(1, events.count { it.kind == EventKind.SIGN_IN })
        assertEquals(1, events.count { it.summary == "课堂消息连接已恢复" })
        assertTrue(updates.filterIsInstance<MonitorUpdate.Connection>().any { it.connected })
        job.cancelAndJoin()
    }
    @Test fun oneRejectedLessonDoesNotPreventAnotherLessonReceivingMessages() = runTest {
        val updates = mutableListOf<MonitorUpdate>()
        val gateway = object : ClassroomGateway {
            override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(12, "34", "受限课程"), ActiveLesson(13, "35", "正常课程"))
            override suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess {
                if (lesson.id == 12L) throw LessonEntryFailure(LessonEntryIssue.DYNAMIC_QR, 400, 50070)
                return LessonAccess(lesson, "token", null)
            }
            override fun frames(account: Account, access: LessonAccess) = flow {
                emit(LessonFrame.Connected)
                emit(LessonFrame.Event(ClassroomEvent(EventKind.QUESTION, "题目", access.lesson.name, access.lesson.id)))
                awaitCancellation()
            }
        }
        val job = backgroundScope.launch { MonitorEngine(gateway, 1_000, elapsed = { testScheduler.currentTime })
            .run(account, updates::add) }
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertTrue(updates.filterIsInstance<MonitorUpdate.Event>().any { it.event.lessonId == 13L && it.event.kind == EventKind.QUESTION })
        assertTrue(updates.filterIsInstance<MonitorUpdate.Connection>().any { it.lessonId == 13L && it.connected })
        assertFalse(updates.filterIsInstance<MonitorUpdate.Connection>().any { it.lessonId == 12L && it.connected })
        job.cancelAndJoin()
    }
    @Test fun repeatedPollsDoNotDuplicateSignInAndCancellationClosesTheSocket() = runTest {
        val gateway = FakeGateway()
        val job = backgroundScope.launch { MonitorEngine(gateway, 1_000).run(account) {} }
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        assertEquals(1, gateway.checkIns)
        job.cancelAndJoin()
        assertEquals(1, gateway.closed)
    }
    @Test fun disablingACourseStopsItsExistingConnection() = runTest {
        val gateway = FakeGateway()
        var excluded = emptySet<String>()
        val job = backgroundScope.launch { MonitorEngine(gateway, 1_000, { excluded }).run(account) {} }
        runCurrent(); excluded = setOf("34")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, gateway.closed)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, gateway.checkIns)
        job.cancelAndJoin()
    }
    @Test fun finishedLessonsAreNotRejoinedUntilTheyDisappearFromThePoll() = runTest {
        val gateway = FakeGateway().apply { finishImmediately = true }
        val job = backgroundScope.launch { MonitorEngine(gateway, 1_000).run(account) {} }
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(1, gateway.checkIns)
        gateway.active = emptyList(); advanceTimeBy(1_000); runCurrent()
        gateway.active = listOf(ActiveLesson(12, "34", "课程")); advanceTimeBy(1_000); runCurrent()
        assertEquals(2, gateway.checkIns)
        job.cancelAndJoin()
    }
    @Test fun stoppingAMonitorCancelsPendingAutomationBeforeItCanSubmit() = runTest {
        val q = Question("42", "43", "9", 1, listOf("A", "B"), 1, "", 60.0, 100_000)
        var submitted = 0
        val gateway = object : ClassroomGateway, AutomationGateway {
            override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(12, "34", "课程"))
            override suspend fun checkIn(account: Account, lesson: ActiveLesson) = LessonAccess(lesson, "t", null)
            override fun frames(account: Account, access: LessonAccess) = flow {
                emit(LessonFrame.Connected)
                emit(LessonFrame.Signal(kotlinx.serialization.json.Json.parseToJsonElement("""{"op":"unlockproblem","problem":{"prob":42,"pres":9}}""").let { it as kotlinx.serialization.json.JsonObject }))
                awaitCancellation()
            }
            override suspend fun presentation(account: Account, access: LessonAccess, id: String) = listOf(q)
            override suspend fun submit(account: Account, access: LessonAccess, question: Question, answer: Answer, now: Long) { submitted++ }
            override suspend fun danmu(account: Account, access: LessonAccess, content: String) = Unit
            override suspend fun bonus(account: Account, access: LessonAccess, id: String) = Unit
            override suspend fun quickAnswerInfo(account: Account, access: LessonAccess, id: JsonPrimitive) = error("unused")
            override suspend fun quickAnswer(account: Account, access: LessonAccess, id: JsonPrimitive) = error("unused")
        }
        val job = backgroundScope.launch {
            MonitorEngine(gateway, automationFactory = { scope, account, access, emit ->
                LessonAutomation(scope, account, access, gateway, AiAnswerer { _, _ -> error("unused") },
                    { CourseSettings(single = AnswerMode.RANDOM, nearDeadline = true) }, { AiSettings() }, emit,
                    { 100_000 + testScheduler.currentTime })
            }).run(account) {}
        }
        runCurrent(); assertEquals(0, submitted)
        job.cancelAndJoin(); advanceTimeBy(70_000); runCurrent()
        assertEquals(0, submitted)
    }
    @Test fun oneExpiredAccountDoesNotStopAnIndependentAccount() = runTest {
        val healthy = FakeGateway()
        val expired = object : ClassroomGateway {
            override suspend fun activeLessons(account: Account): List<ActiveLesson> = throw SessionExpired()
            override suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess = error("unused")
            override fun frames(account: Account, access: LessonAccess) = flow<LessonFrame> { error("unused") }
        }
        var sawExpiry = false
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val healthyJob = owner.launch { MonitorEngine(healthy, 1_000).run(account) {} }
        owner.launch { try { MonitorEngine(expired).run(account) {} } catch (_: SessionExpired) { sawExpiry = true } }
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertTrue(sawExpiry); assertTrue(healthyJob.isActive); assertEquals(1, healthy.checkIns)
        owner.cancel(); runCurrent(); assertEquals(1, healthy.closed)
    }
    @Test fun routineSocketRotationKeepsReceivingEventsWithoutErrorSpam() = runTest {
        var connections = 0
        var checkIns = 0
        val updates = mutableListOf<MonitorUpdate>()
        val gateway = object : ClassroomGateway {
            override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(12, "34", "课程"))
            override suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess {
                checkIns++
                return LessonAccess(lesson, "token", null)
            }
            override fun frames(account: Account, access: LessonAccess) = flow {
                connections++
                emit(LessonFrame.Connected)
                emit(LessonFrame.Event(ClassroomEvent(EventKind.QUESTION, "新题目")))
                delay(45_000)
                throw NetworkFailure("rotation")
            }
        }
        val job = backgroundScope.launch { MonitorEngine(gateway).run(account, updates::add) }
        runCurrent(); advanceTimeBy(190_000); runCurrent()
        assertTrue(connections >= 4)
        assertTrue(updates.filterIsInstance<MonitorUpdate.Event>().count { it.event.kind == EventKind.QUESTION } >= 4)
        assertTrue(updates.filterIsInstance<MonitorUpdate.Event>().none { it.event.kind == EventKind.ERROR })
        assertEquals(1, checkIns)
        job.cancelAndJoin()
    }
    @Test fun repeatedConnectionsWithoutAHelloMustEventuallyWarn() = runTest {
        val updates = mutableListOf<MonitorUpdate>()
        val gateway = object : ClassroomGateway {
            override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(12, "34", "课程"))
            override suspend fun checkIn(account: Account, lesson: ActiveLesson) = LessonAccess(lesson, "token", null)
            override fun frames(account: Account, access: LessonAccess) = flow<LessonFrame> { delay(100) }
        }
        val job = backgroundScope.launch { MonitorEngine(gateway).run(account, updates::add) }
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        assertTrue(updates.filterIsInstance<MonitorUpdate.Event>().any { it.event.kind == EventKind.ERROR })
        job.cancelAndJoin()
    }
    @Test fun sustainedOutageIsRateLimitedAndStoppingCancelsRetryDelay() = runTest {
        val updates = mutableListOf<MonitorUpdate>()
        val gateway = ReconnectingGateway { throw NetworkFailure("sessionid=secret-cookie; token=secret-token") }
        val job = backgroundScope.launch {
            MonitorEngine(gateway, elapsed = { testScheduler.currentTime }).run(account, updates::add)
        }
        runCurrent(); advanceTimeBy(90_000); runCurrent()
        val warnings = updates.filterIsInstance<MonitorUpdate.Event>().filter { it.event.kind == EventKind.ERROR }
        assertEquals(1, warnings.size)
        assertTrue(warnings.none { "secret" in it.event.summary })
        assertTrue(gateway.connections <= 9)
        job.cancelAndJoin()
        val stoppedAt = gateway.connections
        advanceTimeBy(60_000); runCurrent()
        assertEquals(stoppedAt, gateway.connections)
    }
    @Test fun sustainedOutageRefreshesAccessThenReportsOneStableRecovery() = runTest {
        val updates = mutableListOf<MonitorUpdate>()
        val gateway = ReconnectingGateway { number ->
            if (number <= 3) throw NetworkFailure("offline")
            emit(LessonFrame.Connected)
            awaitCancellation()
        }
        val job = backgroundScope.launch {
            MonitorEngine(gateway, elapsed = { testScheduler.currentTime }).run(account, updates::add)
        }
        runCurrent(); advanceTimeBy(18_000); runCurrent()
        val events = updates.filterIsInstance<MonitorUpdate.Event>().map { it.event }
        assertEquals(1, events.count { it.kind == EventKind.ERROR })
        assertEquals(1, events.count { it.summary == "课堂消息连接已恢复" })
        assertEquals(1, events.count { it.kind == EventKind.SIGN_IN })
        assertTrue(events.none { it.summary == "课堂签到成功" })
        assertEquals(2, gateway.checkIns)
        assertEquals(listOf("token-1", "token-1", "token-1", "token-2"), gateway.tokens)
        job.cancelAndJoin()
    }
    @Test fun briefHelloDisconnectLoopsDoNotResetBackoffOrSpamRecovery() = runTest {
        val updates = mutableListOf<MonitorUpdate>()
        val gateway = ReconnectingGateway {
            emit(LessonFrame.Connected)
            delay(100)
            throw NetworkFailure("unstable")
        }
        val job = backgroundScope.launch {
            MonitorEngine(gateway, elapsed = { testScheduler.currentTime }).run(account, updates::add)
        }
        runCurrent(); advanceTimeBy(90_000); runCurrent()
        val events = updates.filterIsInstance<MonitorUpdate.Event>().map { it.event }
        assertEquals(1, events.count { it.kind == EventKind.ERROR })
        assertTrue(events.none { it.summary == "课堂消息连接已恢复" })
        assertTrue(gateway.connections <= 9)
        job.cancelAndJoin()
    }
    @Test fun rejectedLessonCredentialsAreRefreshedWithoutDisablingTheAccount() = runTest {
        val gateway = ReconnectingGateway { number ->
            if (number == 1) throw ClassroomConnectionFailure(ConnectionIssue.CREDENTIALS, 403)
            emit(LessonFrame.Connected)
            awaitCancellation()
        }
        val job = backgroundScope.launch {
            MonitorEngine(gateway, elapsed = { testScheduler.currentTime }).run(account) {}
        }
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertTrue(job.isActive)
        assertEquals(2, gateway.checkIns)
        assertEquals(listOf("token-1", "token-2"), gateway.tokens)
        job.cancelAndJoin()
    }
    @Test fun shortConnectionsDeliveringQuestionsMustNotReportAContinuousOutage() = runTest {
        val updates = mutableListOf<MonitorUpdate>()
        val gateway = ReconnectingGateway {
            emit(LessonFrame.Connected)
            emit(LessonFrame.Event(ClassroomEvent(EventKind.QUESTION, "新题目")))
            delay(5_000)
            throw NetworkFailure("short connection")
        }
        val job = backgroundScope.launch {
            MonitorEngine(gateway, elapsed = { testScheduler.currentTime }).run(account, updates::add)
        }
        runCurrent(); advanceTimeBy(90_000); runCurrent()
        val events = updates.filterIsInstance<MonitorUpdate.Event>().map { it.event }
        assertTrue(events.count { it.kind == EventKind.QUESTION } >= 10)
        assertTrue(events.none { it.kind == EventKind.ERROR })
        assertEquals(1, gateway.checkIns)
        job.cancelAndJoin()
    }
    @Test fun aFailedOptionalRefreshMustNotDiscardPreviouslyWorkingAccess() = runTest {
        var checkIns = 0
        var connections = 0
        val updates = mutableListOf<MonitorUpdate>()
        val gateway = object : ClassroomGateway {
            override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(12, "34", "课程"))
            override suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess {
                if (++checkIns > 1) throw NetworkFailure("refresh unavailable")
                return LessonAccess(lesson, "working-token", null)
            }
            override fun frames(account: Account, access: LessonAccess) = flow {
                if (++connections <= 3) throw NetworkFailure("temporary disconnection")
                assertEquals("working-token", access.lessonToken)
                emit(LessonFrame.Connected)
                emit(LessonFrame.Event(ClassroomEvent(EventKind.QUESTION, "新题目")))
                awaitCancellation()
            }
        }
        val job = backgroundScope.launch {
            MonitorEngine(gateway, elapsed = { testScheduler.currentTime }).run(account, updates::add)
        }
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertTrue(updates.filterIsInstance<MonitorUpdate.Event>().any { it.event.kind == EventKind.QUESTION })
        assertEquals(4, connections)
        assertEquals(2, checkIns)
        job.cancelAndJoin()
    }
    @Test fun usefulStateUpdatesAlsoPreventFalseOutageWarningsOnShortConnections() = runTest {
        val updates = mutableListOf<MonitorUpdate>()
        val gateway = ReconnectingGateway {
            emit(LessonFrame.Connected)
            emit(LessonFrame.Signal(Json.parseToJsonElement("""{"op":"presentationupdated","presentation":"9"}""").jsonObject))
            delay(2_000)
            throw NetworkFailure("rotation")
        }
        val job = backgroundScope.launch {
            MonitorEngine(gateway, elapsed = { testScheduler.currentTime }).run(account, updates::add)
        }
        runCurrent(); advanceTimeBy(45_000); runCurrent()
        assertTrue(gateway.connections >= 10)
        assertTrue(updates.filterIsInstance<MonitorUpdate.Event>().none { it.event.kind == EventKind.ERROR })
        assertEquals(1, gateway.checkIns)
        job.cancelAndJoin()
    }
    @Test fun theFirstRealMessageClearsAnExistingWarningBeforeTheStabilityDelay() = runTest {
        val updates = mutableListOf<MonitorUpdate>()
        val gateway = ReconnectingGateway { number ->
            if (number <= 3) throw NetworkFailure("offline")
            emit(LessonFrame.Connected)
            emit(LessonFrame.Event(ClassroomEvent(EventKind.QUESTION, "新题目")))
            awaitCancellation()
        }
        val job = backgroundScope.launch {
            MonitorEngine(gateway, elapsed = { testScheduler.currentTime }).run(account, updates::add)
        }
        runCurrent(); advanceTimeBy(8_000); runCurrent()
        assertEquals(1, updates.filterIsInstance<MonitorUpdate.Event>().count { it.event.summary == "课堂消息连接已恢复" })
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, updates.filterIsInstance<MonitorUpdate.Event>().count { it.event.summary == "课堂消息连接已恢复" })
        job.cancelAndJoin()
    }
    @Test fun explicitlyRejectedAccessIsNeverUsedWhenItsRefreshFails() = runTest {
        var checkIns = 0
        var connections = 0
        val gateway = object : ClassroomGateway {
            override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(12, "34", "课程"))
            override suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess {
                if (++checkIns > 1) throw NetworkFailure("unavailable", ConnectionIssue.HTTP, 503)
                return LessonAccess(lesson, "rejected-token", null)
            }
            override fun frames(account: Account, access: LessonAccess) = flow<LessonFrame> {
                connections++
                throw ClassroomConnectionFailure(ConnectionIssue.CREDENTIALS, 403)
            }
        }
        val job = backgroundScope.launch {
            MonitorEngine(gateway, elapsed = { testScheduler.currentTime }).run(account) {}
        }
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertEquals(1, connections)
        assertTrue(checkIns >= 3)
        job.cancelAndJoin()
    }
    @Test fun accountExpiryDuringRefreshStillStopsTheMonitor() = runTest {
        var checkIns = 0
        val gateway = object : ClassroomGateway {
            override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(12, "34", "课程"))
            override suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess {
                if (++checkIns > 1) throw SessionExpired()
                return LessonAccess(lesson, "token", null)
            }
            override fun frames(account: Account, access: LessonAccess) = flow<LessonFrame> {
                throw ClassroomConnectionFailure(ConnectionIssue.CREDENTIALS, 403)
            }
        }
        var expired = false
        val job = backgroundScope.launch {
            try { MonitorEngine(gateway).run(account) {} }
            catch (_: SessionExpired) { expired = true }
        }
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertTrue(expired)
        assertFalse(job.isActive)
        assertEquals(2, checkIns)
    }
    @Test fun credentialFailuresReportTheStageAndHttpStatusWithoutExceptionPayloads() = runTest {
        val updates = mutableListOf<MonitorUpdate>()
        val gateway = object : ClassroomGateway {
            override suspend fun activeLessons(account: Account) = listOf(ActiveLesson(12, "34", "课程"))
            override suspend fun checkIn(account: Account, lesson: ActiveLesson): LessonAccess =
                throw NetworkFailure("sessionid=secret-cookie", ConnectionIssue.HTTP, 503)
            override fun frames(account: Account, access: LessonAccess) = flow<LessonFrame> { error("unused") }
        }
        val job = backgroundScope.launch {
            MonitorEngine(gateway, elapsed = { testScheduler.currentTime }).run(account, updates::add)
        }
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        val summary = updates.filterIsInstance<MonitorUpdate.Event>().first { it.event.kind == EventKind.ERROR }.event.summary
        assertTrue(summary.contains("获取课堂凭据"))
        assertTrue(summary.contains("HTTP 503"))
        assertFalse(summary.contains("secret"))
        job.cancelAndJoin()
    }
    @Test fun automationBacklogIsNotMisreportedAsANetworkOutageWhileMessagesArrive() = runTest {
        val updates = mutableListOf<MonitorUpdate>()
        val gateway = ReconnectingGateway {
            emit(LessonFrame.Connected)
            repeat(80) {
                emit(LessonFrame.Signal(buildJsonObject { put("op", "presentationupdated"); put("presentation", "9") }))
            }
            awaitCancellation()
        }
        val actions = object : AutomationGateway {
            override suspend fun presentation(account: Account, access: LessonAccess, id: String): List<Question> = awaitCancellation()
            override suspend fun submit(account: Account, access: LessonAccess, question: Question, answer: Answer, now: Long) = Unit
            override suspend fun danmu(account: Account, access: LessonAccess, content: String) = Unit
            override suspend fun bonus(account: Account, access: LessonAccess, id: String) = Unit
            override suspend fun quickAnswerInfo(account: Account, access: LessonAccess, id: JsonPrimitive) = error("unused")
            override suspend fun quickAnswer(account: Account, access: LessonAccess, id: JsonPrimitive) = error("unused")
        }
        val job = backgroundScope.launch {
            MonitorEngine(gateway, elapsed = { testScheduler.currentTime }, automationFactory = { scope, user, access, emit ->
                LessonAutomation(scope, user, access, actions, AiAnswerer { _, _ -> error("unused") },
                    { CourseSettings() }, { AiSettings() }, emit)
            }).run(account, updates::add)
        }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        val events = updates.filterIsInstance<MonitorUpdate.Event>().map { it.event }
        assertEquals(1, events.count { it.kind == EventKind.ACTION && "积压" in it.summary })
        assertTrue(events.none { it.kind == EventKind.ERROR })
        assertEquals(1, gateway.checkIns)
        job.cancelAndJoin()
    }
}
