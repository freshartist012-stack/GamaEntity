package com.rio.gamaentity

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class AssistantOverlayActivity : Activity() {

    private lateinit var waveformView: WaveformView
    private lateinit var responseText: TextView
    private lateinit var inputField: EditText
    private lateinit var prefs: SharedPreferences
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isActive = true
    private val messages = JSONArray()
    private var systemPromptAdded = false
    private val handler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)

        // Floating window setup
        val params = window.attributes
        params.width = (resources.displayMetrics.widthPixels * 0.92).toInt()
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = 120
        window.attributes = params
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setFinishOnTouchOutside(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            val bg = GradientDrawable().apply {
                setColor(0xFF1A1A2E.toInt())
                cornerRadius = 32f
            }
            background = bg
        }

        // Response text
        responseText = TextView(this).apply {
            text = "Listening..."
            setTextColor(0xFFEEEEEE.toInt())
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        root.addView(responseText)

        // Waveform
        waveformView = WaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 60)
        }
        root.addView(waveformView)

        // Input row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 0)
        }

        inputField = EditText(this).apply {
            hint = "Type here..."
            setHintTextColor(0xFF666666.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(inputField)

        val sendBtn = TextView(this).apply {
            text = "→"
            textSize = 22f
            setTextColor(0xFFCEBAA2.toInt())
            setPadding(16, 0, 8, 0)
            setOnClickListener {
                val text = inputField.text.toString().trim()
                if (text.isNotEmpty()) {
                    inputField.setText("")
                    sendToGAMA(text)
                }
            }
        }
        inputRow.addView(sendBtn)

        val micBtn = TextView(this).apply {
            text = "🎙"
            textSize = 22f
            setPadding(8, 0, 0, 0)
            setOnClickListener { startListening() }
        }
        inputRow.addView(micBtn)

        root.addView(inputRow)

        // Bottom row
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 12, 0, 0)
        }

        val expandBtn = TextView(this).apply {
            text = "Open GAMA ↗"
            textSize = 11f
            setTextColor(0xFFCEBAA2.toInt())
            setPadding(0, 0, 24, 0)
            setOnClickListener {
                startActivity(Intent(this@AssistantOverlayActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
                finish()
            }
        }
        bottomRow.addView(expandBtn)

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setOnClickListener { finish() }
        }
        bottomRow.addView(closeBtn)
        root.addView(bottomRow)

        setContentView(root)

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

        handler.postDelayed({ startListening() }, 400)
    }

    private fun startListening() {
        if (!isActive) return
        responseText.text = "Listening..."
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!transcript.isNullOrEmpty() && isActive) {
                    responseText.text = "You: $transcript"
                    sendToGAMA(transcript)
                } else if (isActive) {
                    handler.postDelayed({ startListening() }, 300)
                }
            }
            override fun onError(error: Int) {
                if (isActive) {
                    responseText.text = "Tap 🎙 to speak"
                }
            }
            override fun onReadyForSpeech(params: Bundle?) { responseText.text = "Speak now..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { waveformView.updateAmplitude(rmsdB * 200) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { waveformView.updateAmplitude(0f) }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrEmpty()) responseText.text = partial
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    private fun sendToGAMA(transcript: String) {
        responseText.text = "Thinking..."
        val groqKey = prefs.getString("groq_key", "") ?: ""
        val userName = prefs.getString("user_name", "User") ?: "User"
        if (groqKey.isEmpty()) { responseText.text = "No API key"; return }

        if (!systemPromptAdded) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", """You are GAMA, a concise AI voice assistant. User: $userName.
Be very brief. Only output commands when explicitly asked:
CALL:NUMBER, GOOGLE:query, YOUTUBE:query, FLASHLIGHT:ON/OFF, OPEN_APP:name, ALARM:HH:MM:Label, WHATSAPP:NUMBER:MESSAGE""")
            })
            systemPromptAdded = true
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", transcript)
        })

        client.newCall(Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $groqKey")
            .addHeader("Content-Type", "application/json")
            .post(JSONObject().apply {
                put("model", "openai/gpt-oss-120b")
                put("messages", messages)
                put("max_tokens", 200)
                put("tool_choice", "none")
            }.toString().toRequestBody("application/json".toMediaType())).build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { responseText.text = "Connection error"; if (isActive) handler.postDelayed({ startListening() }, 1000) }
            }
            override fun onResponse(call: Call, response: Response) {
                val b = response.body?.string()
                handler.post {
                    try {
                        val json = JSONObject(b ?: "")
                        if (json.has("error")) { responseText.text = "Try again"; if (isActive) startListening(); return@post }
                        val reply = json.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content")
                            .replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()

                        messages.put(JSONObject().apply { put("role", "assistant"); put("content", reply) })
                        responseText.text = reply

                        val hasCommand = handleAction(reply)

                        if (ttsReady && !hasCommand) {
                            tts?.speak(reply.replace(Regex("[*_#]"), "").take(200), TextToSpeech.QUEUE_FLUSH, null, "done")
                            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(u: String?) {}
                                override fun onDone(u: String?) { handler.postDelayed({ if (isActive) startListening() }, 400) }
                                override fun onError(u: String?) { handler.post { if (isActive) startListening() } }
                            })
                        } else if (!hasCommand) {
                            handler.postDelayed({ if (isActive) startListening() }, 800)
                        }
                    } catch (e: Exception) {
                        responseText.text = "Error"
                        if (isActive) handler.postDelayed({ startListening() }, 1000)
                    }
                }
            }
        })
    }

    private fun handleAction(reply: String): Boolean {
        for (line in reply.split("\n")) {
            val t = line.trim()

            Regex("(?i)FLASHLIGHT:(ON|OFF)").find(t)?.let {
                try {
                    val cm = getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    cm.setTorchMode(cm.cameraIdList[0], it.groupValues[1].uppercase() == "ON")
                } catch (e: Exception) {}
                return true
            }

            Regex("(?i)ALARM:(\\d{1,2}):(\\d{2})(?::(.+))?").find(t)?.let {
                val hour = it.groupValues[1].toIntOrNull() ?: return false
                val minute = it.groupValues[2].toIntOrNull() ?: return false
                try {
                    startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it.groupValues[3].ifEmpty { "GAMA Alarm" })
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    })
                } catch (e: Exception) {}
                return true
            }

            // All other commands — launch GAMA to handle with confirmation screens
            if (t.contains(Regex("(?i)(CALL:|GOOGLE:|YOUTUBE:|OPEN_APP:|WHATSAPP:|PLEASE_CALL:|SPOTIFY:|YOUTUBE_MUSIC:)"))) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("notif_command", reply)
                }
                startActivity(intent)
                finish()
                return true
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }
}
