package net.pangolin.Pangolin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTriggerPolicyTest {
    private val triggers = setOf("com.example.trigger")

    private fun enter(foreground: String, tunnelRunning: Boolean, state: AppTriggerState = AppTriggerState()): AppTriggerDecision =
        AppTriggerPolicy.evaluate(foreground, triggers, tunnelRunning, alwaysOnActive = null, state = state)

    @Test
    fun openingTriggerAppWhileTunnelOffStartsTunnelAndLatches() {
        val decision = enter("com.example.trigger", tunnelRunning = false)

        assertEquals(AppTriggerAction.START_TUNNEL, decision.action)
        assertTrue(decision.state.isTriggerForeground)
        assertFalse(decision.state.wasRunningBeforeTrigger == true)
    }

    @Test
    fun closingTriggerAppStopsTunnelOnlyWhenFeatureStartedIt() {
        val entering = enter("com.example.trigger", tunnelRunning = false)
        val leaving = enter("com.example.other", tunnelRunning = true, state = entering.state)

        assertEquals(AppTriggerAction.STOP_TUNNEL, leaving.action)
        assertFalse(leaving.state.isTriggerForeground)
        assertNull(leaving.state.wasRunningBeforeTrigger)
    }

    @Test
    fun openingTriggerAppWhileTunnelRunningLeavesItAloneAndLatches() {
        val decision = enter("com.example.trigger", tunnelRunning = true)

        assertEquals(AppTriggerAction.NONE, decision.action)
        assertTrue(decision.state.isTriggerForeground)
        assertTrue(decision.state.wasRunningBeforeTrigger == true)
    }

    @Test
    fun closingTriggerAppThatWasAlreadyOnDoesNotStopTunnel() {
        val entering = enter("com.example.trigger", tunnelRunning = true)
        val leaving = enter("com.example.other", tunnelRunning = true, state = entering.state)

        assertEquals(AppTriggerAction.NONE, leaving.action)
    }

    @Test
    fun remainingOutsideTriggerAppDoesNothing() {
        val decision = enter("com.example.other", tunnelRunning = false)

        assertEquals(AppTriggerAction.NONE, decision.action)
        assertFalse(decision.state.isTriggerForeground)
    }

    @Test
    fun stayingInTriggerAppDoesNothing() {
        val entering = enter("com.example.trigger", tunnelRunning = false)
        val same = enter("com.example.trigger", tunnelRunning = true, state = entering.state)

        assertEquals(AppTriggerAction.NONE, same.action)
        assertTrue(same.state.isTriggerForeground)
    }

    @Test
    fun alwaysOnActiveSkipsAutoActions() {
        val decision = AppTriggerPolicy.evaluate(
            foregroundApp = "com.example.trigger",
            triggerPackages = triggers,
            tunnelRunning = false,
            alwaysOnActive = true,
            state = AppTriggerState(),
        )

        assertEquals(AppTriggerAction.NONE, decision.action)
    }

    @Test
    fun alwaysOnActiveLeavesLatchFree() {
        val insideAlwaysOn = AppTriggerPolicy.evaluate(
            foregroundApp = "com.example.trigger",
            triggerPackages = triggers,
            tunnelRunning = false,
            alwaysOnActive = true,
            state = AppTriggerState(),
        )
        val outsideAlwaysOn = AppTriggerPolicy.evaluate(
            foregroundApp = "com.example.other",
            triggerPackages = triggers,
            tunnelRunning = false,
            alwaysOnActive = true,
            state = insideAlwaysOn.state,
        )

        assertEquals(AppTriggerAction.NONE, outsideAlwaysOn.action)
        assertNull(outsideAlwaysOn.state.wasRunningBeforeTrigger)
    }

    @Test
    fun nullForegroundNeverForcesTransition() {
        val decision = AppTriggerPolicy.evaluate(
            foregroundApp = null,
            triggerPackages = triggers,
            tunnelRunning = false,
            alwaysOnActive = null,
            state = AppTriggerState(),
        )

        assertEquals(AppTriggerAction.NONE, decision.action)
    }

    @Test
    fun triggerAppAlreadyForegroundAtStartupActsLikeEntering() {
        val decision = enter("com.example.trigger", tunnelRunning = false)

        assertEquals(AppTriggerAction.START_TUNNEL, decision.action)
        assertFalse(decision.state.wasRunningBeforeTrigger == true)
    }
}