package com.rio.gamaentity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class ConfirmVoice(private val act: Activity, private val onHeard: (Kind, String) -> Unit, private val onIdle: () -> Unit) {
    enum class Kind { CONFIRM, CANCEL, EDIT }

    private var rec: SpeechRecognizer? = null
    private val h = Handler(Looper.getMainLooper())
    private var active = false
    private var misses = 0

    companion object {
        private val yesSet = setOf("proceed", "continue", "yes", "yeah", "yep", "confirm", "send", "okay", "ok", "install", "call", "sure")
        private val noSet = setOf("cancel", "stop", "no", "nope", "abort")
        private val filler = setOf("please", "it", "that", "this", "now", "thanks", "thank", "you", "the")

        fun classify(text: String): Kind {
            var t = text.lowercase().replace(Regex("[^a-z' ]"), " ")
            t = t.replace("go ahead", "proceed").replace("do it", "proceed").replace("never mind", "cancel").replace("nevermind", "cancel")
            val words = t.split(Regex(" +")).filter { it.isNotEmpty() && it !in filler }
            if (words.isEmpty()) return Kind.EDIT
            if (words.all { it in yesSet }) return Kind.CONFIRM
            if (words.all { it in noSet }) return Kind.CANCEL
            return Kind.EDIT
        }
    }

    fun start() {
        active = true
        misses = 0
        listen()
    }

    fun listenAgain() = start()

    fun stop() {
        active = false
        h.removeCallbacksAndMessages(null)
        try { rec?.destroy() } catch (e: Exception) {}
        rec = null
    }

    private fun retry() {
        if (!active) return
        misses++
        if (misses >= 3) {
            active = false
            onIdle()
        } else {
            h.postDelayed({ listen() }, 400)
        }
    }

    private fun listen() {
        if (!active) return
        try {
            rec?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(act)
            rec = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onError(e: Int) { retry() }
                override fun onResults(res: Bundle?) {
                    val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) { retry(); return }
                    misses = 0
                    active = false
                    onHeard(classify(t), t)
                }
            })
            val lang = act.getSharedPreferences("gama_prefs", Activity.MODE_PRIVATE).getString("app_language", "en-ZA") ?: "en-ZA"
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            r.startListening(i)
        } catch (e: Exception) {
            active = false
            onIdle()
        }
    }
}
