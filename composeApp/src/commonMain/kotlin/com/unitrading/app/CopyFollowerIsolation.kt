package com.unitrading.app

internal enum class CopyFollowerRunState { RUNNING, WAITING_FOR_RECONNECT, NEEDS_REVIEW, STOPPED }
internal data class CopyFollowerRunStatus(val state: CopyFollowerRunState, val reason: String? = null)

/** Plans account-local pauses and reconnects. No trading or retry is performed here. */
internal object CopyFollowerIsolation {
    fun fail(statuses: Map<String, CopyFollowerRunStatus>, followerId: String, reason: String): Map<String, CopyFollowerRunStatus> {
        if (followerId !in statuses) return statuses
        return statuses + (followerId to CopyFollowerRunStatus(CopyFollowerRunState.NEEDS_REVIEW, reason))
    }
    fun disconnected(statuses: Map<String, CopyFollowerRunStatus>): Map<String, CopyFollowerRunStatus> = statuses.mapValues { (_, status) ->
        if (status.state == CopyFollowerRunState.RUNNING) CopyFollowerRunStatus(CopyFollowerRunState.WAITING_FOR_RECONNECT, "连接中断，等待账户核对") else status
    }
    /** Restart also starts in WAITING_FOR_RECONNECT, never directly in RUNNING. */
    fun afterRestart(statuses: Map<String, CopyFollowerRunStatus>): Map<String, CopyFollowerRunStatus> = disconnected(statuses)
    fun reconciled(statuses: Map<String, CopyFollowerRunStatus>, result: CopyRecoveryResult): Map<String, CopyFollowerRunStatus> =
        statuses.mapValues { (id, status) ->
            if (status.state != CopyFollowerRunState.WAITING_FOR_RECONNECT) status
            else {
                val reason = result.leaderReason ?: if (id in result.followerReasons) result.followerReasons[id] else "缺少账户核对结果"
                if (reason == null) CopyFollowerRunStatus(CopyFollowerRunState.RUNNING)
                else CopyFollowerRunStatus(CopyFollowerRunState.WAITING_FOR_RECONNECT, reason)
            }
        }
}
