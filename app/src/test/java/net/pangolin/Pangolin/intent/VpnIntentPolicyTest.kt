package net.pangolin.Pangolin.intent

import net.pangolin.Pangolin.util.TunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnIntentPolicyTest {
    private val disconnected = TunnelState()
    private val connected = TunnelState(isServiceRunning = true, isSocketConnected = true, isRegistered = true)
    private val connecting = TunnelState(isConnecting = true)

    @Test
    fun connectCanBeAppliedOnlyWhenTheTunnelCanBeEnabled() {
        assertTrue(VpnIntentPolicy.canApply(VpnIntentPolicy.ACTION_CONNECT_VPN, disconnected))
        assertFalse(VpnIntentPolicy.canApply(VpnIntentPolicy.ACTION_CONNECT_VPN, connected))
        assertFalse(VpnIntentPolicy.canApply(VpnIntentPolicy.ACTION_CONNECT_VPN, connecting))
    }

    @Test
    fun disconnectCanBeAppliedOnlyWhenTheTunnelCanBeDisabled() {
        assertFalse(VpnIntentPolicy.canApply(VpnIntentPolicy.ACTION_DISCONNECT_VPN, disconnected))
        assertTrue(VpnIntentPolicy.canApply(VpnIntentPolicy.ACTION_DISCONNECT_VPN, connected))
        assertTrue(VpnIntentPolicy.canApply(VpnIntentPolicy.ACTION_DISCONNECT_VPN, connecting))
    }

    @Test
    fun unknownActionsAreNeverApplied() {
        assertFalse(VpnIntentPolicy.canApply("unknown.action", disconnected))
        assertFalse(VpnIntentPolicy.canApply("unknown.action", connected))
    }
}