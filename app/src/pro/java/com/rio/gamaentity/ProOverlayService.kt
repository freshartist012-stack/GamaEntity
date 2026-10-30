package com.rio.gamaentity

import android.os.Handler
import android.os.Looper

class ProOverlayService : OverlayService() {

    override fun getSystemPromptExtra(): String {
        if (!GamaAccessibilityService.isEnabled) return ""
        return " [Navigation commands, output only the command: BACK, HOME, MINIMIZE, SCROLL:UP, SCROLL:DOWN, SCROLL:LEFT, SCROLL:RIGHT. One command per reply. Use only when the user asks to navigate.]"
    }

    override fun handleScreenAction(reply: String): Boolean {
        for (line in reply.split("\n")) {
            val t = line.trim()
            val upper = t.uppercase()
            val query = when {
                upper.startsWith("SPOTIFY:") -> t.substring(8).trim()
                upper.startsWith("YOUTUBE_MUSIC:") -> t.substring(14).trim()
                else -> ""
            }
            if (query.isNotEmpty()) {
                Handler(Looper.getMainLooper()).postDelayed({
                    GamaAccessibilityService.playBestMatch(query)
                }, 1500)
                return false
            }
        }
        return handleNavigation(reply)
    }

    private fun handleNavigation(reply: String): Boolean {
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
