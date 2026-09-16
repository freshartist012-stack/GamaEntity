package com.rio.gamaentity

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
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
    private lateinit var inputField: EditText
    private lateinit var responseText: TextView
    private lateinit var prefs: SharedPreferences
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
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

        // Make it a small floating window
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window.setGravity(Gravity.BOTTOM)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setFinishOnTouchOutside(true)

        // Build UI
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFF1A1A2E.toInt())
        }

        // Top row - waveform + input
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        waveformView = WaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(120, 80)
        }
        topRow.addView(waveformView)

        inputField = EditText(this).apply {
            hint = "Or type here..."
            setHintTextColor(0xFF888888.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2A2A4E.toInt())
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(16, 0, 0, 0)
            }
        }
        topRow.addView(inputField)

        val sendBtn = TextView(this).apply {
            text = "→"
            textSize = 20f
            setTextColor(0xFFCEBAA2.toInt())
            setPadding(16, 0, 0, 0)
            setOnClickListener {
                val text = inputField.text.toString().trim()
                if (text.isNotEmpty()) {
                    inputField.setText("")
                    sendToGAMA(text)
                }
            }
        }
        topRow.addView(sendBtn)

        root.addView(topRow)

        // Response text
        responseText = TextView(this).apply {
            text = "Listening..."
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        root.addView(responseText)

        // Bottom row - expand + close
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 12, 0, 0)
        }

        val expandBtn = TextView(this).apply {
            text = "Open GAMA ↗"
            textSize = 12f
            setTextColor(0xFFCEBAA2.toInt())
            setPadding(0, 0, 24, 0)
            setOnClickListener {
                startActivity(Intent(this@AssistantOverlayActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                finish()
            }
        }
        bottomRow.addView(expandBtn)

        val closeBtn = TextView(this).apply {
            text = "✕ Close"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setOnClickListener { finish() }
        }
        bottomRow.addView(closeBtn)
        root.addView(bottomRow)

        setContentView(root)

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

        // Auto start listening
        handler.postDelayed({ startListening() }, 300)
    }

    private fun startListening() {
        responseText.text = "Listening..."
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                inputField.setText(transcript)
                sendToGAMA(transcript)
            }
            override fun onError(error: Int) {
                responseText.text = "Tap mic or type below"
                waveformView.updateAmplitude(0f)
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
        if (groqKey.isEmpty()) { responseText.text = "No API key set"; return }

        if (!systemPromptAdded) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", """You are GAMA, a concise AI assistant. User: $userName. 
Be very brief in responses. Only output commands when explicitly asked:
CALL:NUMBER, GOOGLE:query, YOUTUBE:query, FLASHLIGHT:ON/OFF, OPEN_APP:name, ALARM:HH:MM:Label""")
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
                handler.post { responseText.text = "Connection error. Try again." }
            }
            override fun onResponse(call: Call, response: Response) {
                val b = response.body?.string()
                handler.post {
                    try {
                        val json = JSONObject(b ?: "")
                        if (json.has("error")) { responseText.text = "Try again."; return@post }
                        val reply = json.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content")
                            .replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()

                        messages.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", reply)
                        })

                        responseText.text = reply
                        handleAction(reply)

                        if (ttsReady) {
                            tts?.speak(reply.replace(Regex("[*_#]"), "").take(200),
                                TextToSpeech.QUEUE_FLUSH, null, "done")
                            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(u: String?) {}
                                override fun onDone(u: String?) { handler.postDelayed({ startListening() }, 300) }
                                override fun onError(u: String?) {}
                            })
                        }
                    } catch (e: Exception) {
                        responseText.text = "Error. Try again."
                    }
                }
            }
        })
    }

    private fun handleAction(reply: String) {
        for (line in reply.split("\n")) {
            val t = line.trim()

            Regex("(?i)FLASHLIGHT:(ON|OFF)").find(t)?.let {
                try {
                    val cm = getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    cm.setTorchMode(cm.cameraIdList[0], it.groupValues[1].uppercase() == "ON")
                } catch (e: Exception) {}
                return
            }
            Regex("(?i)CALL:([^\\n]+)").find(t)?.let {
                startActivity(Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:${it.groupValues[1].trim()}")))
                return
            }
            Regex("(?i)GOOGLE:(.+)").find(t)?.let {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode(it.groupValues[1].trim())}")))
                return
            }
            Regex("(?i)YOUTUBE:(.+)").find(t)?.let {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=${android.net.Uri.encode(it.groupValues[1].trim())}")))
                return
            }
            Regex("(?i)OPEN_APP:(.+)").find(t)?.let {
                val appName = it.groupValues[1].trim().lowercase()
                val found = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .firstOrNull { ri -> ri.loadLabel(packageManager).toString().lowercase().contains(appName) }
                found?.let { ri -> packageManager.getLaunchIntentForPackage(ri.activityInfo.packageName)?.let { startActivity(it) } }
                return
            }
            Regex("(?i)ALARM:(\\d{1,2}):(\\d{2})(?::(.+))?").find(t)?.let {
                val hour = it.groupValues[1].toIntOrNull() ?: return
                val minute = it.groupValues[2].toIntOrNull() ?: return
                try {
                    startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it.groupValues[3].ifEmpty { "GAMA Alarm" })
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    })
                } catch (e: Exception) {}
                return
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }
}
