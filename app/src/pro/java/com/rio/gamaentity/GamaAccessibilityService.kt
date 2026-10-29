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
            return when {
                action.startsWith("CLICK:") -> {
                    val t = action.removePrefix("CLICK:").trim()
                    val list = elements()
                    val idx = t.toIntOrNull()
                    val node = if (idx != null) list.getOrNull(idx - 1)
                               else list.firstOrNull { labelOf(it).contains(t, ignoreCase = true) }
                    if (node == null) false else clickNode(node)
                }
                action.startsWith("SCROLL:") -> {
                    val up = action.removePrefix("SCROLL:").trim().uppercase() == "UP"
                    val act = if (up) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                              else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    val root = targetRoot()
                    val sc = if (root == null) null else findScrollable(root)
                    sc?.performAction(act) ?: false
                }
                action.startsWith("TYPE:") -> {
                    val text = action.removePrefix("TYPE:").trim()
                    val root = targetRoot() ?: return false
                    val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
                    val args = Bundle()
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    target?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ?: false
                }
                action == "BACK" -> service.performGlobalAction(GLOBAL_ACTION_BACK)
                action == "HOME" -> service.performGlobalAction(GLOBAL_ACTION_HOME)
                else -> false
            }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isEnabled = false
    }
}
