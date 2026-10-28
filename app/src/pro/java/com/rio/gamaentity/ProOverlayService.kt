package com.rio.gamaentity

import android.os.Handler
import android.os.Looper

class ProOverlayService : OverlayService() {

    override fun getSystemPromptExtra(): String {
        if (!GamaAccessibilityService.isEnabled) return ""
        val uiTree = GamaAccessibilityService.getUITree()
        if (uiTree.isEmpty()) return ""
        return "\n\nCURRENT SCREEN:\n$uiTree\n\nScreen commands (one per response):\nCLICK:n or CLICK:text\nSCROLL:DOWN or SCROLL:UP\nTYPE:text\nBACK\nHOME\nOnly use when user asks to interact with screen."
    }

    override fun handleScreenAction(reply: String): Boolean {
        val screenActions = listOf("CLICK:", "SCROLL:", "TYPE:", "BACK", "HOME")
        for (line in reply.split("\n")) {
            val t = line.trim()
            val isAction = screenActions.any { t.startsWith(it) || t == it }
            if (isAction) {
                val executed = GamaAccessibilityService.executeAction(t)
                if (executed) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        startListening()
                    }, 1000)
                    return true
                }
            }
        }
        return false
    }
}
