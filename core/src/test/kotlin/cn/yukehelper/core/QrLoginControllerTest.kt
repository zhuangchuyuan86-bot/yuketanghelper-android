package cn.yukehelper.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QrLoginControllerTest {
    private val site = "pro.yuketang.cn"
    private val user = Account("1", "测试", site, "private-session")

    @Test fun confirmationAfterTwoMinutesKeepsTheSameTicketAndSavesBeforeSuccess() = runTest {
        var starts = 0; var saved = 0
        val image = byteArrayOf(1, 2, 3)
        lateinit var controller: QrLoginController
        controller = QrLoginController(backgroundScope, { flow {
            starts++; emit(LoginState.QrCode(image)); delay(120_000)
            emit(LoginState.Verifying); emit(LoginState.Success(user))
        } }, { assertEquals(QrLoginPhase.SAVING, controller.state.value.phase); saved++ })
        val attempt = controller.start(site)
        runCurrent(); advanceTimeBy(119_000); runCurrent()
        assertEquals(QrLoginPhase.WAITING, controller.state.value.phase)
        assertSame(image, controller.state.value.image)
        assertEquals(attempt, controller.start(site))
        advanceTimeBy(1_001); runCurrent()
        assertEquals(QrLoginPhase.SUCCESS, controller.state.value.phase)
        assertEquals(1, starts); assertEquals(1, saved)
        assertNull(controller.state.value.image)
        assertFalse(controller.state.value.toString().contains("private"))
    }

    @Test fun screenObserversCanDisappearAndReturnWithoutCancellingTheLogin() = runTest {
        val confirmed = CompletableDeferred<Unit>(); var saved = false; var closed = 0
        val controller = QrLoginController(backgroundScope, { flow {
            try { emit(LoginState.QrCode(byteArrayOf(1))); confirmed.await(); emit(LoginState.Success(user)) }
            finally { closed++ }
        } }, { saved = true })
        controller.start(site)
        val screen = backgroundScope.launch { controller.state.collect() }
        runCurrent(); screen.cancel(); runCurrent()
        assertEquals(0, closed)
        val recreated = backgroundScope.launch { controller.state.collect() }
        assertEquals(controller.state.value.attempt, controller.start(site))
        confirmed.complete(Unit); runCurrent()
        assertTrue(saved); assertEquals(QrLoginPhase.SUCCESS, controller.state.value.phase)
        recreated.cancel()
    }

    @Test fun manualRefreshClosesThePreviousAttemptAndIgnoresItsCancelAction() = runTest {
        var starts = 0; var closed = 0
        val controller = QrLoginController(backgroundScope, { flow {
            try { starts++; emit(LoginState.QrCode(byteArrayOf(starts.toByte()))); awaitCancellation() }
            finally { closed++ }
        } }, {})
        val old = controller.start(site); runCurrent()
        val current = controller.start(site, force = true); runCurrent()
        assertNotEquals(old, current); assertEquals(2, starts); assertEquals(1, closed)
        controller.cancel(old, "old notification")
        assertEquals(QrLoginPhase.WAITING, controller.state.value.phase)
        controller.cancel(current); runCurrent()
        assertEquals(QrLoginPhase.IDLE, controller.state.value.phase); assertEquals(2, closed)
    }

    @Test fun theFiveMinuteWaitIsBoundedAndDiscardsTheOldImage() = runTest {
        var closed = false
        val controller = QrLoginController(backgroundScope, { flow {
            try { emit(LoginState.QrCode(byteArrayOf(1))); awaitCancellation() }
            finally { closed = true }
        } }, { fail("An expired attempt must not save an account") })
        controller.start(site); runCurrent()
        advanceTimeBy(299_999); runCurrent()
        assertEquals(QrLoginPhase.WAITING, controller.state.value.phase)
        advanceTimeBy(1); runCurrent()
        assertEquals(QrLoginPhase.FAILED, controller.state.value.phase)
        assertTrue(controller.state.value.message.contains("5 分钟"))
        assertNull(controller.state.value.image); assertTrue(closed)
    }

    @Test fun cancelDuringVerificationDoesNotSaveLaterResults() = runTest {
        var saved = false
        val controller = QrLoginController(backgroundScope, { flow {
            emit(LoginState.Verifying); delay(5_000); emit(LoginState.Success(user))
        } }, { saved = true })
        val attempt = controller.start(site); runCurrent()
        controller.cancel(attempt, "已取消"); advanceTimeBy(6_000); runCurrent()
        assertFalse(saved); assertEquals("已取消", controller.state.value.message)
    }

    @Test fun changingSiteDiscardsThePreviousPendingAccount() = runTest {
        val saved = mutableListOf<String>()
        val controller = QrLoginController(backgroundScope, { domain -> flow {
            delay(if (domain == site) 10_000 else 10)
            emit(LoginState.Success(Account("1", "同学", domain, "private")))
        } }, { saved += it.domain })
        controller.start(site); runCurrent()
        controller.start("www.yuketang.cn"); runCurrent(); advanceTimeBy(11_000); runCurrent()
        assertEquals(listOf("www.yuketang.cn"), saved)
    }

    @Test fun saveFailureNeverReportsSuccessOrExposesExceptionText() = runTest {
        val controller = QrLoginController(backgroundScope, { flowOf(LoginState.Success(user)) }, {
            error("private-session private-auth private-path")
        })
        controller.start(site); runCurrent()
        assertEquals(QrLoginPhase.FAILED, controller.state.value.phase)
        assertTrue(controller.state.value.message.contains("本机保存"))
        assertFalse(controller.state.value.message.contains("private"))
    }

    @Test fun refreshCannotInterruptAnAccountAlreadyBeingSaved() = runTest {
        val saving = CompletableDeferred<Unit>(); var saved = 0
        val controller = QrLoginController(backgroundScope, { flowOf(LoginState.Success(user)) }, { saving.await(); saved++ })
        val attempt = controller.start(site); runCurrent()
        assertEquals(QrLoginPhase.SAVING, controller.state.value.phase)
        assertEquals(attempt, controller.start(site, force = true))
        saving.complete(Unit); runCurrent()
        assertEquals(1, saved); assertEquals(QrLoginPhase.SUCCESS, controller.state.value.phase)
    }

    @Test fun unexpectedFlowTerminationOffersANewAttemptInsteadOfWaitingForever() = runTest {
        val controller = QrLoginController(backgroundScope, { emptyFlow() }, {})
        controller.start(site); runCurrent()
        assertEquals(QrLoginPhase.FAILED, controller.state.value.phase)
        assertFalse(controller.state.value.active)
        val previous = controller.state.value.attempt
        assertNotEquals(previous, controller.start(site, force = true)); runCurrent()
    }

    @Test fun safeNumericDiagnosticsAreRetainedWithoutTheRawResponse() = runTest {
        val controller = QrLoginController(backgroundScope, { flow { throw LoginFailure(LoginStage.USER, 503, 50400) } }, {})
        controller.start(site); runCurrent()
        assertTrue(controller.state.value.message.contains("HTTP 503"))
        assertTrue(controller.state.value.message.contains("50400"))
        assertEquals(QrLoginPhase.FAILED, controller.state.value.phase)
    }
}
