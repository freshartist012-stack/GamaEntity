package com.rio.gamaentity

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GamaAccessibilityService : AccessibilityService() {

    companion object {
        var instance: GamaAccessibilityService? = null
        var pendingWhatsAppSend = false
        var pendingAlarmDismiss = false
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val root = rootInActiveWindow ?: return

        if (pendingWhatsAppSend && pkg == "com.whatsapp") {
            val sendButton = findNodeByDescription(root, listOf("send", "Send"))
            if (sendButton != null) {
                sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                pendingWhatsAppSend = false
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }

        if (pendingAlarmDismiss && (pkg.contains("clock") || pkg.contains("alarm") || pkg.contains("deskclock"))) {
            val dismissButton = findNodeByDescription(root, listOf("dismiss", "Dismiss", "stop", "Stop", "turn off", "Turn off"))
            if (dismissButton != null) {
                dismissButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                pendingAlarmDismiss = false
            }
        }
    }

    private fun findNodeByDescription(node: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName ?: ""
        if (keywords.any { desc.contains(it.lowercase()) || text.contains(it.lowercase()) || id.lowercase().contains(it.lowercase()) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByDescription(child, keywords)
            if (result != null) return result
        }
        return null
    }

    override fun onInterrupt() { instance = null }
    override fun onDestroy() { instance = null }
}
