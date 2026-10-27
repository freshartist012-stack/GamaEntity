package com.rio.gamaentity

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GamaAccessibilityService : AccessibilityService() {

    companion object {
        var instance: GamaAccessibilityService? = null
        var isEnabled = false

        fun getUITree(): String {
            val service = instance ?: return ""
            val root = service.rootInActiveWindow ?: return ""
            return buildUITree(root, 0).take(4000)
        }

        fun executeAction(action: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false

            return when {
                action.startsWith("CLICK:") -> {
                    val target = action.removePrefix("CLICK:").trim()
                    val index = target.toIntOrNull()
                    if (index != null) {
                        clickByIndex(root, index)
                    } else {
                        clickByText(root, target)
                    }
                }
                action.startsWith("SCROLL:") -> {
                    val direction = action.removePrefix("SCROLL:").trim().uppercase()
                    scrollScreen(root, direction)
                }
                action.startsWith("TYPE:") -> {
                    val text = action.removePrefix("TYPE:").trim()
                    typeText(root, text)
                }
                action == "BACK" -> {
                    service.performGlobalAction(GLOBAL_ACTION_BACK)
                    true
                }
                action == "HOME" -> {
                    service.performGlobalAction(GLOBAL_ACTION_HOME)
                    true
                }
                else -> false
            }
        }

        private fun buildUITree(node: AccessibilityNodeInfo, depth: Int): String {
            val sb = StringBuilder()
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val className = node.className?.toString()?.substringAfterLast(".") ?: ""
            val isClickable = node.isClickable
            val label = when {
                text.isNotEmpty() -> text
                desc.isNotEmpty() -> desc
                else -> return buildChildTree(node, depth)
            }
            val type = when {
                className.contains("Button") -> "BUTTON"
                className.contains("Edit") -> "INPUT"
                className.contains("Text") && isClickable -> "LINK"
                className.contains("Text") -> "TEXT"
                isClickable -> "CLICKABLE"
                else -> "ELEMENT"
            }
            sb.append("[$type] $label\n")
            sb.append(buildChildTree(node, depth + 1))
            return sb.toString()
        }

        private fun buildChildTree(node: AccessibilityNodeInfo, depth: Int): String {
            if (depth > 5) return ""
            val sb = StringBuilder()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                sb.append(buildUITree(child, depth))
            }
            return sb.toString()
        }

        private fun clickByIndex(root: AccessibilityNodeInfo, index: Int): Boolean {
            val clickables = mutableListOf<AccessibilityNodeInfo>()
            collectClickable(root, clickables)
            val target = clickables.getOrNull(index - 1) ?: return false
            return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        private fun clickByText(root: AccessibilityNodeInfo, text: String): Boolean {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            return nodes?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        }

        private fun collectClickable(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
            if (node.isClickable) list.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectClickable(child, list)
            }
        }

        private fun scrollScreen(root: AccessibilityNodeInfo, direction: String): Boolean {
            val action = if (direction == "UP") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            return findScrollable(root)?.performAction(action) ?: false
        }

        private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val result = findScrollable(node.getChild(i) ?: continue)
                if (result != null) return result
            }
            return null
        }

        private fun typeText(root: AccessibilityNodeInfo, text: String): Boolean {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return focused?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ?: false
        }
    }

    override fun onServiceConnected() {
        instance = this
        isEnabled = true
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        instance = null
        isEnabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isEnabled = false
    }
}
