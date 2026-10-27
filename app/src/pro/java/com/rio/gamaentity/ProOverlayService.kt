package com.rio.gamaentity

import android.content.Intent
import android.os.Handler
import android.os.Looper

class ProOverlayService : OverlayService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun getSystemPromptExtra(): String {
        if (!GamaAccessibilityService.isEnabled) return ""
        val uiTree = GamaAccessibilityService.getUITree()
        if (uiTree.isEmpty()) return ""
        return "\n\nCURRENT SCREEN:\n$uiTree\n\nYou can control the screen with these commands (one action per response):\nCLICK:n (click nth clickable element)\nCLICK:text (click element with this text)\nSCROLL:DOWN or SCROLL:UP\nTYPE:text (type into focused input)\nBACK\nHOME\nOnly use screen commands when user explicitly asks to interact with the screen."
    }

    override fun handleScreenAction(reply: String): Boolean {
        val actions = listOf("CLICK:", "SCROLL:", "TYPE:", "BACK", "HOME")
        for (line in reply.split("\n")) {
            val t = line.trim()
            for (action in actions) {
                if (t.startsWith(action) || t == action) {
                    val executed = GamaAccessibilityService.executeAction(t)
                    if (executed) {
                        handler.postDelayed({
                            handler.post { updateStatus("Done. Listening...") }
                            startListening()
                        }, 1000)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun updateStatus(text: String) {
        if (::responseText.isInitialized) responseText.text = text
    }
}
