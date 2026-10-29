package com.rio.gamaentity

import android.os.Handler
import android.os.Looper

class ProOverlayService : OverlayService() {

    override fun getSystemPromptExtra(): String {
        if (!GamaAccessibilityService.isEnabled) return ""
        val uiTree = GamaAccessibilityService.getUITree()
        if (uiTree.isEmpty()) return ""
        return "\n\nCURRENT SCREEN:\n$uiTree\n\n" +
            "SCREEN CONTROL RULES:\n" +
            "- One action per response only. Never chain multiple actions.\n" +
            "- After executing one action, stop and wait for next instruction.\n" +
            "- Commands: CLICK:n, CLICK:text, SCROLL:DOWN, SCROLL:UP, TYPE:text, BACK, HOME\n" +
            "- Only use commands when user explicitly asks to interact with screen.\n" +
            "- Confirm what you did after each action in one short sentence."
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
                    }, 1200)
                    return true
                }
            }
        }
        return false
    }
}
