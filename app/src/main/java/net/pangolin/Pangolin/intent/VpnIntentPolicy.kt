package net.pangolin.Pangolin.intent

import net.pangolin.Pangolin.util.TunnelState

/**
 * Maps third-party broadcast intents to tunnel actions and decides whether they
 * can be applied to the current tunnel state.
 *
 * These action names are the public contract for on-demand VPN automation (e.g.
 * Tasker or microdroid) and must not be renamed without a coordinated release.
 */
internal object VpnIntentPolicy {
    const val ACTION_CONNECT_VPN = "net.pangolin.Pangolin.CONNECT_VPN"
    const val ACTION_DISCONNECT_VPN = "net.pangolin.Pangolin.DISCONNECT_VPN"

    fun canApply(action: String, state: TunnelState): Boolean = when (action) {
        ACTION_CONNECT_VPN -> state.canEnable
        ACTION_DISCONNECT_VPN -> state.canDisable
        else -> false
    }
}