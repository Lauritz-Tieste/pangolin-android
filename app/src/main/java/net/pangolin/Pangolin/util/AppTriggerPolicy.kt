package net.pangolin.Pangolin.util

/**
 * Decides what the app-trigger monitor must do when the foreground app changes.
 *
 * The tunnel is brought up while one of the configured apps is in the foreground and is brought
 * back down once it is closed, but only when the tunnel was not already running before that app
 * was opened. If it was already running, the user's choice is left untouched.
 */
enum class AppTriggerAction {
    START_TUNNEL,
    STOP_TUNNEL,
    NONE,
}

/** Tracks the trigger relationship between the foreground apps and the tunnel. */
internal data class AppTriggerState(
    /** Whether one of the configured trigger apps is currently in the foreground. */
    val isTriggerForeground: Boolean = false,
    /**
     * Whether the tunnel was running just before the trigger app entered the foreground.
     * Non-null while [isTriggerForeground] is true.
     */
    val wasRunningBeforeTrigger: Boolean? = null,
)

internal data class AppTriggerDecision(
    val action: AppTriggerAction,
    val state: AppTriggerState,
)

internal object AppTriggerPolicy {
    /**
     * Evaluate one foreground change.
     *
     * [foregroundApp] is the currently detected foreground package (null if unknown). The policy
     * only reacts to transitions between "a trigger app is foreground" and "no trigger app is
     * foreground"; a null foreground never forces a transition by itself.
     *
     * [alwaysOnActive] being true skips all automatic actions so the platform Always-On
     * reconnect cannot be fought by this feature.
     */
    fun evaluate(
        foregroundApp: String?,
        triggerPackages: Set<String>,
        tunnelRunning: Boolean,
        alwaysOnActive: Boolean?,
        state: AppTriggerState,
    ): AppTriggerDecision {
        val triggerForeground = foregroundApp != null && foregroundApp in triggerPackages

        // While Always-On owns the tunnel, never take automatic actions. Track the foreground
        // so the state stays truthful, but keep the latch free so a later manual transition
        // starts from a clean slate.
        if (alwaysOnActive == true) {
            return AppTriggerDecision(
                AppTriggerAction.NONE,
                AppTriggerState(isTriggerForeground = triggerForeground, wasRunningBeforeTrigger = null),
            )
        }

        // No transition in the trigger/foreground relationship.
        if (triggerForeground == state.isTriggerForeground) {
            return AppTriggerDecision(AppTriggerAction.NONE, state)
        }

        if (triggerForeground) {
            // A trigger app just entered the foreground. Remember whether the tunnel was already
            // running so we can restore that state when the app is closed again.
            return AppTriggerDecision(
                if (tunnelRunning) AppTriggerAction.NONE else AppTriggerAction.START_TUNNEL,
                AppTriggerState(isTriggerForeground = true, wasRunningBeforeTrigger = tunnelRunning),
            )
        }

        // The trigger app left the foreground. Only bring the tunnel down if this feature is the
        // one that brought it up (it was not running before the app was opened).
        val shouldStop = state.wasRunningBeforeTrigger == false
        return AppTriggerDecision(
            if (shouldStop) AppTriggerAction.STOP_TUNNEL else AppTriggerAction.NONE,
            AppTriggerState(isTriggerForeground = false, wasRunningBeforeTrigger = null),
        )
    }
}