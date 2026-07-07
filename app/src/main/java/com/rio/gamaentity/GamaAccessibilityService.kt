package com.rio.gamaentity

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
            val sendButton = findNodeByKeywords(root, listOf("send", "Send"))
            if (sendButton != null) {
                sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                pendingWhatsAppSend = false
                Handler(Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    Handler(Looper.getMainLooper()).postDelayed({
                        val intent = packageManager?.getLaunchIntentForPackage("com.rio.gamaentity")
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        }
                    }, 600)
                }, 1200)
            }
        }

        if (pendingAlarmDismiss && (pkg.contains("clock") || pkg.contains("alarm") || pkg.contains("deskclock"))) {
            val dismissButton = findNodeByKeywords(root, listOf("dismiss", "stop", "turn off"))
            if (dismissButton != null) {
                dismissButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                pendingAlarmDismiss = false
                Handler(Looper.getMainLooper()).postDelayed({
                    val intent = packageManager?.getLaunchIntentForPackage("com.rio.gamaentity")
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    }
                }, 800)
            }
        }
    }

    private fun findNodeByKeywords(node: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName?.lowercase() ?: ""
        if (keywords.any { desc.contains(it) || text.contains(it) || id.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByKeywords(child, keywords)
            if (result != null) return result
        }
        return null
    }

    override fun onInterrupt() { instance = null }
    override fun onDestroy() { instance = null }
}
