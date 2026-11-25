package com.unitrading

import kotlinx.coroutines.CancellationException

/** Commit or restore the workspace before closing, then persist non-workspace application data. */
internal suspend fun prepareDesktopExit(
    saveWorkspace: Boolean,
    saveWorkspaceNow: (suspend () -> Boolean)?,
    discardWorkspace: () -> Unit,
    resumeWorkspaceWrites: () -> Unit,
    saveApplication: suspend () -> Boolean,
): String? {
    if (saveWorkspace) {
        val saved = try { saveWorkspaceNow?.invoke() ?: true }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
        if (!saved) return "窗口布局或绘图保存失败，请重试。"
    } else {
        try { discardWorkspace() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return "无法恢复退出前的窗口设置，请重试。" }
    }
    val saved = try { saveApplication() }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { false }
    if (!saved) {
        if (!saveWorkspace) resumeWorkspaceWrites()
        return "账户数据保存失败，请重试。"
    }
    return null
}
