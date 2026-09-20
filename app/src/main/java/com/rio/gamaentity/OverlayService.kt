package com.rio.gamaentity

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.*
import android.widget.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isActive = false
    private val messages = JSONArray()
    private var systemPromptAdded = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var responseText: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var inputField: EditText

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        var isRunning = false
        const val ACTION_SHOW = "SHOW_OVERLAY"
        const val ACTION_HIDE = "HIDE_OVERLAY"
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
        }
        return START_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            val bg = GradientDrawable().apply {
                setColor(0xF01A1A2E.toInt())
                cornerRadius = 32f
            }
            background = bg
        }

        responseText = TextView(this).apply {
            text = "Listening..."
            setTextColor(0xFFEEEEEE.toInt())
            textSize = 15f
            setPadding(0, 0, 0, 12)
        }
        root.addView(responseText)

        waveformView = WaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 56)
        }
        root.addView(waveformView)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 0)
        }

        inputField = EditText(this).apply {
            hint = "Type here..."
            setHintTextColor(0xFF666666.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(inputField)

        val sendBtn = TextView(this).apply {
            text = "→"
            textSize = 20f
            setTextColor(0xFFCEBAA2.toInt())
            setPadding(12, 0, 8, 0)
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
            textSize = 20f
            setPadding(8, 0, 0, 0)
            setOnClickListener { startListening() }
        }
        inputRow.addView(micBtn)

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFF888888.toInt())
            setPadding(16, 0, 0, 0)
            setOnClickListener { hideOverlay() }
        }
        inputRow.addView(closeBtn)
        root.addView(inputRow)

        val expandBtn = TextView(this).apply {
            text = "Open GAMA ↗"
            textSize = 11f
            setTextColor(0xFFCEBAA2.toInt())
            setPadding(0, 8, 0, 0)
            setOnClickListener {
                startActivity(Intent(this@OverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                hideOverlay()
            }
        }
        root.addView(expandBtn)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        overlayView = root
        windowManager?.addView(root, params)
        isActive = true
        handler.postDelayed({ startListening() }, 400)
    }

    private fun hideOverlay() {
        isActive = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        stopSelf()
    }

    private fun startListening() {
        if (!isActive) return
        handler.post {
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
                    if (isActive) responseText.text = "Tap 🎙 to speak"
                }
                override fun onReadyForSpeech(p: Bundle?) { responseText.text = "Speak..." }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { handler.post { waveformView.updateAmplitude(rmsdB * 200) } }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { handler.post { waveformView.updateAmplitude(0f) } }
                override fun onPartialResults(p: Bundle?) {
                    val partial = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrEmpty()) handler.post { responseText.text = partial }
                }
                override fun onEvent(e: Int, p: Bundle?) {}
            })
            speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        }
    }

    private fun sendToGAMA(transcript: String) {
        val prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)
        val groqKey = prefs.getString("groq_key", "") ?: ""
        val userName = prefs.getString("user_name", "User") ?: "User"
        if (groqKey.isEmpty()) { handler.post { responseText.text = "No API key" }; return }

        handler.post { responseText.text = "Thinking..." }

        if (!systemPromptAdded) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", "You are GAMA, a concise AI voice assistant. User: $userName. Be very brief. Only output commands when explicitly asked: CALL:NUMBER, GOOGLE:query, YOUTUBE:query, FLASHLIGHT:ON/OFF, OPEN_APP:name, ALARM:HH:MM:Label, WHATSAPP:NUMBER:MESSAGE")
            })
            systemPromptAdded = true
        }
        messages.put(JSONObject().apply { put("role", "user"); put("content", transcript) })

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
                handler.post { responseText.text = "Error"; if (isActive) handler.postDelayed({ startListening() }, 1000) }
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
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {}
                return true
            }

            Regex("(?i)CALL:([^\n]+)").find(t)?.let {
                val number = lookupContact(it.groupValues[1].trim())
                try { startActivity(Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:$number")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
                return true
            }

            Regex("(?i)GOOGLE:(.+)").find(t)?.let {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode(it.groupValues[1].trim())}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                return true
            }

            Regex("(?i)YOUTUBE:(.+)").find(t)?.let {
                val uri = android.net.Uri.parse("https://www.youtube.com/results?search_query=${android.net.Uri.encode(it.groupValues[1].trim())}")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    setPackage("com.google.android.youtube")
                }
                try { startActivity(intent) } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                return true
            }

            Regex("(?i)OPEN_APP:(.+)").find(t)?.let {
                val appName = it.groupValues[1].trim().lowercase()
                val found = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .firstOrNull { ri -> ri.loadLabel(packageManager).toString().lowercase().contains(appName) }
                found?.let { ri -> packageManager.getLaunchIntentForPackage(ri.activityInfo.packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }?.let { startActivity(it) } }
                return true
            }

            Regex("(?i)WHATSAPP:([^:]+):(.+)").find(t)?.let {
                startActivity(Intent(this, AssistantOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("execute_command", reply)
                })
                return true
            }

            Regex("(?i)PLEASE_CALL:(.+)").find(t)?.let {
                startActivity(Intent(this, AssistantOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("execute_command", reply)
                })
                return true
            }

            Regex("(?i)GMAIL:(.+)").find(t)?.let {
                startActivity(Intent(this, AssistantOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("execute_command", reply)
                })
                return true
            }

            Regex("(?i)SPOTIFY:(.+)").find(t)?.let {
                val query = it.groupValues[1].trim()
                val spotifyUri = android.net.Uri.parse("spotify:search:$query")
                val intent = Intent(Intent.ACTION_VIEW, spotifyUri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { startActivity(intent) } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://open.spotify.com/search/${android.net.Uri.encode(query)}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                return true
            }

            Regex("(?i)YOUTUBE_MUSIC:(.+)").find(t)?.let {
                val query = it.groupValues[1].trim()
                try { startActivity(Intent(Intent.ACTION_SEARCH).apply { setPackage("com.google.android.apps.youtube.music"); putExtra("query", query); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
                catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com/search?q=${android.net.Uri.encode(query)}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
                return true
            }
        }
        return false
    }

    private fun lookupContact(nameOrNumber: String): String {
        val digits = nameOrNumber.replace("[^\\d]".toRegex(), "")
        if (digits.length >= 7) return formatNumber(nameOrNumber)
        try {
            contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    if (name.lowercase().contains(nameOrNumber.lowercase())) return formatNumber(number)
                }
            }
        } catch (e: Exception) {}
        return nameOrNumber
    }

    private fun formatNumber(raw: String): String {
        val d = raw.replace("[^\\d]".toRegex(), "")
        return when {
            d.startsWith("27") && d.length >= 11 -> d
            d.startsWith("0") && d.length == 10 -> "27${d.substring(1)}"
            d.length == 9 -> "27$d"
            else -> d
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isActive = false
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        overlayView?.let { windowManager?.removeView(it) }
    }
}
