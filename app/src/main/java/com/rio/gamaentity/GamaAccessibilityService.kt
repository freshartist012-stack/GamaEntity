package com.rio.gamaentity

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GamaAccessibilityService : AccessibilityService() {

    companion object {
        var instance: GamaAccessibilityService? = null
        var pendingSend = false
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!pendingSend) return
        if (event?.packageName != "com.whatsapp") return

        val root = rootInActiveWindow ?: return
        val sendButton = findSendButton(root)
        if (sendButton != null) {
            sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            pendingSend = false
        }
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName ?: ""
        if (desc.contains("send") || id.contains("send")) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSendButton(child)
            if (result != null) return result
        }
        return null
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
    }
}
