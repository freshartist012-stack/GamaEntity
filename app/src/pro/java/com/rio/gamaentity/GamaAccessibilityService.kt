package com.rio.gamaentity

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class GamaAccessibilityService : AccessibilityService() {

    companion object {
        var instance: GamaAccessibilityService? = null
        var isEnabled = false
        @Volatile var lastApp: String = ""
        @Volatile var onTaskDone: (() -> Unit)? = null

        fun returnToLastApp() {
            val service = instance ?: return
            val pkg = lastApp
            if (pkg.isEmpty()) {
                service.performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
            val intent = service.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                service.performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            service.startActivity(intent)
            onTaskDone?.invoke()
            onTaskDone = null
        }

        private fun targetRoot(): AccessibilityNodeInfo? {
            val service = instance ?: return null
            val own = service.packageName
            var best: AccessibilityNodeInfo? = null
            for (w in service.windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() == own) continue
                if (best == null || w.isActive) best = r
                if (w.isActive) break
            }
            return best ?: service.rootInActiveWindow
        }

        private fun labelOf(n: AccessibilityNodeInfo): String {
            return (n.text ?: n.contentDescription)?.toString().orEmpty().trim()
        }

        private fun collect(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
            if (depth > 25 || out.size >= 60) return
            if (n.isVisibleToUser && (labelOf(n).isNotEmpty() || n.isClickable)) out.add(n)
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                collect(c, out, depth + 1)
            }
        }

        private fun elements(): List<AccessibilityNodeInfo> {
            val root = targetRoot() ?: return emptyList()
            val list = mutableListOf<AccessibilityNodeInfo>()
            collect(root, list, 0)
            return list
        }

        fun getUITree(): String {
            val root = targetRoot() ?: return ""
            val sb = StringBuilder("App: ${root.packageName}\n")
            elements().forEachIndexed { i, n ->
                val cls = n.className?.toString().orEmpty()
                val type = when {
                    cls.contains("Edit") -> "INPUT"
                    cls.contains("Button") -> "BUTTON"
                    n.isClickable -> "CLICKABLE"
                    else -> "TEXT"
                }
                val label = labelOf(n).ifEmpty { "(unlabeled)" }.take(80)
                sb.append("${i + 1}. [$type] $label\n")
            }
            return sb.toString().take(4000)
        }

        fun executeAction(action: String): Boolean {
            val service = instance ?: return false
            return when (action.trim().uppercase()) {
                "BACK" -> service.performGlobalAction(GLOBAL_ACTION_BACK)
                "HOME" -> service.performGlobalAction(GLOBAL_ACTION_HOME)
                "MINIMIZE" -> service.performGlobalAction(GLOBAL_ACTION_RECENTS)
                "SCROLL:DOWN" -> scrollDir(true)
                "SCROLL:UP" -> scrollDir(false)
                "SCROLL:LEFT" -> swipe(0.8f, 0.5f, 0.2f, 0.5f)
                "SCROLL:RIGHT" -> swipe(0.2f, 0.5f, 0.8f, 0.5f)
                else -> false
            }
        }

        private fun scrollDir(forward: Boolean): Boolean {
            val root = targetRoot()
            val sc = if (root == null) null else findScrollable(root)
            val act = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (sc != null && sc.performAction(act)) return true
            return if (forward) swipe(0.5f, 0.75f, 0.5f, 0.25f) else swipe(0.5f, 0.25f, 0.5f, 0.75f)
        }

        private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
            val service = instance ?: return false
            val m = service.resources.displayMetrics
            val path = Path()
            path.moveTo(m.widthPixels * x1, m.heightPixels * y1)
            path.lineTo(m.widthPixels * x2, m.heightPixels * y2)
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            return service.dispatchGesture(g, null, null)
        }

        fun playBestMatch(query: String, leaveAfter: Boolean = false) {
            Thread {
                val words = query.lowercase().split(" ").filter { it.length > 1 }
                if (words.isEmpty()) return@Thread
                var tries = 0
                while (tries < 16) {
                    Thread.sleep(500)
                    tries++
                    val root = targetRoot() ?: continue
                    val best = bestRow(root, words, query.lowercase().trim())
                    if (best != null) {
                        clickNode(best)
                        if (leaveAfter) {
                            Thread.sleep(1500)
                            returnToLastApp()
                        }
                        return@Thread
                    }
                }
            }.start()
        }

        private fun rowText(n: AccessibilityNodeInfo, depth: Int = 0): String {
            if (depth > 8) return ""
            val sb = StringBuilder(labelOf(n)).append(" ")
            for (i in 0 until n.childCount) {
                val ch = n.getChild(i) ?: continue
                sb.append(rowText(ch, depth + 1))
            }
            return sb.toString()
        }

        private fun bestRow(root: AccessibilityNodeInfo, words: List<String>, full: String): AccessibilityNodeInfo? {
            var best: AccessibilityNodeInfo? = null
            var bestScore = 0
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 25) return
                if (n.isClickable && n.isVisibleToUser && !n.isEditable) {
                    val t = rowText(n).lowercase().trim()
                    if (t != full) {
                        val score = words.count { t.contains(it) }
                        if (score > bestScore) {
                            best = n
                            bestScore = score
                        }
                    }
                }
                for (i in 0 until n.childCount) {
                    val ch = n.getChild(i) ?: continue
                    walk(ch, depth + 1)
                }
            }
            walk(root, 0)
            return if (bestScore * 100 >= words.size * 60) best else null
        }

        @JvmStatic fun sendWhatsApp() {
            Thread {
                var tries = 0
                while (tries < 16) {
                    Thread.sleep(500)
                    tries++
                    val root = targetRoot() ?: continue
                    val nodes = root.findAccessibilityNodeInfosByText("Send")
                    val btn = nodes?.firstOrNull { it.isVisibleToUser && !it.isEditable }
                    if (btn != null) {
                        clickNode(btn)
                        Thread.sleep(1500)
                        returnToLastApp()
                        return@Thread
                    }
                }
            }.start()
        }

        private fun clickNode(node: AccessibilityNodeInfo): Boolean {
            var cur: AccessibilityNodeInfo? = node
            while (cur != null && !cur.isClickable) cur = cur.parent
            if (cur != null && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val b = Rect()
            node.getBoundsInScreen(b)
            return gestureClick(b.centerX().toFloat(), b.centerY().toFloat())
        }

        fun gestureClick(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path()
            path.moveTo(x, y)
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            return service.dispatchGesture(g, null, null)
        }

        private fun findScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) {
                val r = findScrollable(n.getChild(i) ?: continue)
                if (r != null) return r
            }
            return null
        }

        private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isEditable) return n
            for (i in 0 until n.childCount) {
                val r = findEditable(n.getChild(i) ?: continue)
                if (r != null) return r
            }
            return null
        }
    }

    override fun onServiceConnected() {
        instance = this
        isEnabled = true
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName || pkg == "com.android.systemui") return
            val launcher = packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
                0
            )?.activityInfo?.packageName
            if (pkg == launcher) return
            if (pkg in listOf("com.spotify.music", "com.whatsapp", "com.google.android.apps.youtube.music", "com.google.android.youtube")) return
            lastApp = pkg
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isEnabled = false
    }
}
