package net.pangolin.Pangolin.intent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.PangolinApplication

/**
 * Handles on-demand VPN control requests from third-party apps (e.g. Tasker or
 * microdroid). Other apps can toggle the VPN by broadcasting an intent with one
 * of the actions declared in VpnIntentPolicy:
 *
 *     net.pangolin.Pangolin.CONNECT_VPN
 *     net.pangolin.Pangolin.DISCONNECT_VPN
 */
class PangolinVpnReceiver : BroadcastReceiver() {
    private val tag = "PangolinVpnReceiver"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context?.applicationContext as? PangolinApplication ?: return

        val tunnelManager = app.runtime.tunnelManager
        val state = tunnelManager.tunnelState.value

        if (!VpnIntentPolicy.canApply(action, state)) {
            Log.w(tag, "Intent $action ignored: tunnel is not in a suitable state (${state.statusMessage})")
            return
        }

        when (action) {
            VpnIntentPolicy.ACTION_CONNECT_VPN -> scope.launch {
                tunnelManager.connect()
            }
            VpnIntentPolicy.ACTION_DISCONNECT_VPN -> scope.launch {
                if (!app.runtime.disconnectFromUser()) {
                    Log.w(tag, "DISCONNECT_VPN ignored: Android Always-On owns the tunnel")
                }
            }
        }
    }
}