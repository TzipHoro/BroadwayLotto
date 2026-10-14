package com.broadwaylotto.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Screen-reading and tapping helpers shared by the per-app runners (TodayTix, Hamilton). Everything
 * is found by view id, text or content description, never by screen position.
 */
internal abstract class AppRunner(protected val svc: AccessibilityService, protected val pkg: String) {
    protected val startedAt = SystemClock.elapsedRealtime()

    /** Thrown to end a run safely: the message is shown to the user. */
    protected class Stop(message: String) : Exception(message)

    // ------------------------------------------------------------------ screen helpers

    protected fun root(): AccessibilityNodeInfo? = svc.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }

    protected fun descendants(): Sequence<AccessibilityNodeInfo> = root()?.descendantsOf() ?: emptySequence()

    protected fun AccessibilityNodeInfo.descendantsOf(): Sequence<AccessibilityNodeInfo> = sequence {
        for (i in 0 until childCount) {
            val c = getChild(i) ?: continue
            yield(c)
            yieldAll(c.descendantsOf())
        }
    }

    protected fun AccessibilityNodeInfo.idIs(id: String) = viewIdResourceName == "$pkg:id/$id"

    protected fun findId(id: String) = descendants().firstOrNull { it.idIs(id) }
    protected fun findText(text: String) = descendants().firstOrNull { it.text?.toString()?.trim() == text }
    protected fun findDesc(desc: String) = descendants().firstOrNull { it.contentDescription?.toString()?.trim().equals(desc, ignoreCase = true) }

    protected fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    /** Scrolls the biggest scrollable area on screen. Returns false when it can't scroll further. */
    protected fun scroll(forward: Boolean): Boolean {
        val scrollables = descendants().filter { it.isScrollable }.toList()
        fun height(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }.height()

        // Prefer the explicit up/down actions: "forward" on a swipeable pager (Hamilton's BROADWAY /
        // TAMPA / ORLANDO tabs) means the NEXT TAB, which is not what we want.
        val vertical = if (forward) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
        else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
        scrollables.filter { vertical in it.actionList }.maxByOrNull(::height)?.let { return it.performAction(vertical.id) }

        // Otherwise the generic forward/backward, but never on something that can move sideways.
        val sideways = listOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT,
        )
        val list = scrollables.filter { n -> sideways.none { it in n.actionList } }.maxByOrNull(::height) ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return list.performAction(action)
    }

    /**
     * A real touch at the centre of [node], for buttons that ignore the accessibility click action.
     * Needs `canPerformGestures` in the service config. Returns whether the gesture completed.
     */
    protected suspend fun tap(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val sent = svc.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: android.accessibilityservice.GestureDescription?) { if (cont.isActive) cont.resume(true) {} }
                    override fun onCancelled(g: android.accessibilityservice.GestureDescription?) { if (cont.isActive) cont.resume(false) {} }
                },
                null,
            )
            if (!sent && cont.isActive) cont.resume(false) {}
        }
    }

    protected fun back() = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    protected suspend fun <T : Any> waitFor(timeoutMs: Long, probe: () -> T?): T? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            probe()?.let { return it }
            delay(300)
        }
        return null
    }

    // ------------------------------------------------------------------ safety

    /** Whole-run time limit, so a stuck screen can't keep this going forever. */
    protected fun checkBudget() {
        if (SystemClock.elapsedRealtime() - startedAt > 6 * 60_000) throw Stop("took too long")
    }

    /** Stops if TodayTix is asking for a login, payment or verification, none of which we ever touch. */
    protected fun guardUnexpected() {
        val flagged = listOf("log in", "sign in", "card number", "payment", "verify", "captcha", "security check")
        descendants().mapNotNull { it.text?.toString() }.firstOrNull { t -> flagged.any { t.contains(it, ignoreCase = true) } }
            ?.let { throw Stop("TodayTix showed a screen I won't touch (\"${it.take(40)}\")") }
    }
}
