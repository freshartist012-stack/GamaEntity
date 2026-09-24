package com.rio.gamaentity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class VoiceCaptureActivity : Activity() {

    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fully transparent
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.decorView.alpha = 0f

        val prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)
        val appLang = prefs.getString("app_language", "en-ZA") ?: "en-ZA"
        val parts = appLang.split("-")
        val locale = if (parts.size == 2) Locale(parts[0], parts[1]) else Locale.getDefault()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!transcript.isNullOrEmpty()) {
                    val broadcast = Intent("com.rio.gamaentity.VOICE_RESULT").apply {
                        putExtra("transcript", transcript)
                        setPackage(packageName)
                    }
                    sendBroadcast(broadcast)
                }
                finish()
            }
            override fun onError(error: Int) { finish() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                val broadcast = Intent("com.rio.gamaentity.VOICE_RMS").apply {
                    putExtra("rms", rmsdB)
                    setPackage(packageName)
                }
                sendBroadcast(broadcast)
            }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {
                val partial = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrEmpty()) {
                    val broadcast = Intent("com.rio.gamaentity.VOICE_PARTIAL").apply {
                        putExtra("partial", partial)
                        setPackage(packageName)
                    }
                    sendBroadcast(broadcast)
                }
            }
            override fun onEvent(e: Int, p: Bundle?) {}
        })

        speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }
}
