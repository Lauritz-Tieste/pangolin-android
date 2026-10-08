package net.pangolin.Pangolin.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tracks which app is currently in the foreground and toggles the tunnel based on the apps the
 * user configured in Settings:
 *
 *  - Opening a configured app starts the tunnel (only if it was not already running).
 *  - Closing it again stops the tunnel, but only when the tunnel was brought up by this feature
 *    (i.e. it was not running before the app was opened).
 *
 * Foreground detection runs through [UsageStatsManager], which needs the "Usage access" special
 * permission, and uses a lightweight polling loop over recent activity-resume events. The monitor
 * lives as long as the process does, so it only works while the process is alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppTriggerMonitor(
    private val context: Context,
    private val tunnelManager: TunnelManager,
    private val configManager: ConfigManager,
) {
    private val tag = "AppTriggerMonitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    @Suppress("DEPRECATION")
    private val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

    private var enabled = false
    private var triggerPackages: Set<String> = emptySet()
    private var state = AppTriggerState()
    private var lastForeground: String? = null
    private var pollingJob: Job? = null

    /** Starts the monitor as part of the app process. */
    fun start() {
        scope.launch {
            configManager.config.collectLatest { config ->
                applyConfig(config)
                runCheck()
            }
        }
    }

    /** Re-evaluates the current state. Call when the user may have granted usage access. */
    fun refresh() {
        scope.launch {
            applyConfig(configManager.config.value)
            runCheck()
        }
    }

    fun stop() {
        scope.cancel()
    }

    private fun applyConfig(config: Config) {
        enabled = config.appTriggerEnabled && config.appTriggerPackages.isNotEmpty()
        triggerPackages = config.appTriggerPackages
        Log.d(
            tag,
            "Config applied: enabled=$enabled, packages=$triggerPackages, " +
                "usageAccess=${hasUsageAccess()}, tunnelRunning=${tunnelManager.tunnelState.value.isServiceRunning}",
        )
        // A changed configuration starts from a clean slate, and the check below re-evaluates so
        // an already-open trigger app takes effect immediately.
        state = AppTriggerState()
        lastForeground = null
    }

    private suspend fun runCheck() {
        if (enabled && hasUsageAccess()) {
            ensurePolling()
            checkNow()
        } else {
            if (enabled && !hasUsageAccess()) {
                Log.w(tag, "App-based activation enabled but Usage Access is not granted; detection is inactive")
            }
            stopPolling()
        }
    }

    private fun checkNow() {
        val foreground = queryForegroundPackage() ?: return
        if (foreground == lastForeground) return
        decide(foreground)
    }

    private fun decide(foreground: String) {
        val previous = lastForeground
        lastForeground = foreground
        val decision = AppTriggerPolicy.evaluate(
            foregroundApp = foreground,
            triggerPackages = triggerPackages,
            tunnelRunning = tunnelManager.tunnelState.value.isServiceRunning,
            alwaysOnActive = tunnelManager.platformAlwaysOnState(),
            state = state,
        )
        state = decision.state
        Log.d(tag, "Foreground $previous -> $foreground; action=${decision.action}, state=$state")
        when (decision.action) {
            AppTriggerAction.START_TUNNEL -> {
                Log.i(tag, "Trigger app $foreground opened; starting tunnel")
                scope.launch {
                    val result = tunnelManager.connectFromStoredAccount()
                    Log.i(tag, "Trigger connect returned $result")
                    if (result == TunnelStartResult.TERMINAL_FAILURE) {
                        Log.w(tag, "Trigger connect failed terminally. Make sure the VPN was authorized " +
                            "once in the app (toggle the connection manually once).")
                    }
                }
            }
            AppTriggerAction.STOP_TUNNEL -> {
                Log.i(tag, "Trigger app $foreground closed; stopping tunnel")
                scope.launch {
                    runCatching { tunnelManager.disconnect() }.onFailure { Log.e(tag, "Trigger disconnect failed", it) }
                }
            }
            AppTriggerAction.NONE -> Unit
        }
    }

    private fun ensurePolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                checkNow()
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Returns the currently foreground package by looking at the most recent resume event in the
     * look-back window, or null when the system reports nothing (screen off, permission missing…).
     */
    private fun queryForegroundPackage(): String? {
        if (!hasUsageAccess()) return null
        return try {
            val endTime = System.currentTimeMillis()
            val beginTime = endTime - FOREGROUND_LOOKBACK_MS
            val events = usageStatsManager.queryEvents(beginTime, endTime)
            val event = UsageEvents.Event()
            var foreground: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (isResumeEvent(event)) {
                    foreground = packageNameOf(event)
                }
            }
            foreground
        } catch (e: Exception) {
            Log.w(tag, "Failed to query foreground package", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun isResumeEvent(event: UsageEvents.Event): Boolean {
        // ACTIVITY_RESUMED only exists on API 29+; MOVE_TO_FOREGROUND is its pre-29 equivalent.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            mEventTypeForPreP(event) == UsageEvents.Event.MOVE_TO_FOREGROUND
        }
    }

    private fun mEventTypeForPreP(event: UsageEvents.Event): Int {
        return try {
            UsageEvents.Event::class.java.getField("mEventType").getInt(event)
        } catch (e: Exception) {
            -1
        }
    }

    private fun packageNameOf(event: UsageEvents.Event): String? {
        // Event.getPackageName() only exists on API 28+; mPackageName is its public-field equivalent.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            event.packageName
        } else {
            try {
                UsageEvents.Event::class.java.getField("mPackageName").get(event) as? String
            } catch (e: Exception) {
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean {
        return try {
            val op = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
            op == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val FOREGROUND_LOOKBACK_MS = 2 * 60 * 1000L
    }
}