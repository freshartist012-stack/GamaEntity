package com.rio.gamaentity

import android.app.Service
import android.net.Uri
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
        val filter = android.content.IntentFilter().apply {
            addAction("com.rio.gamaentity.VOICE_RESULT")
            addAction("com.rio.gamaentity.VOICE_PARTIAL")
            addAction("com.rio.gamaentity.VOICE_RMS")
        }
        registerReceiver(voiceReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val appLang = getSharedPreferences("gama_prefs", android.content.Context.MODE_PRIVATE).getString("app_language", "en-ZA") ?: "en-ZA"
                val parts = appLang.split("-")
                val locale = if (parts.size == 2) java.util.Locale(parts[0], parts[1]) else java.util.Locale.getDefault()
                tts?.language = locale
            }
            if (ttsReady) {
                val appLang = getSharedPreferences("gama_prefs", android.content.Context.MODE_PRIVATE).getString("app_language", "en-ZA") ?: "en-ZA"
                val parts = appLang.split("-")
                val locale = if (parts.size == 2) java.util.Locale(parts[0], parts[1]) else java.util.Locale.getDefault()
                tts?.language = locale
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
        }
        return START_STICKY
    }

    private var voiceEnabled = true
    private lateinit var voiceBtn: TextView
    private lateinit var overlayParams: WindowManager.LayoutParams

    private fun showOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 32)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF01A1A2E.toInt())
                cornerRadius = 32f
            }
            background = bg
        }

        // Drag handle row
        val dragRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }

        val dragHandle = TextView(this).apply {
            text = "⠿ GAMA"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dragRow.addView(dragHandle)

        voiceBtn = TextView(this).apply {
            text = "🎙 On"
            textSize = 12f
            setTextColor(0xFF4CAF50.toInt())
            setPadding(0, 0, 16, 0)
            setOnClickListener {
                voiceEnabled = !voiceEnabled
                if (voiceEnabled) {
                    text = "🎙 On"
                    setTextColor(0xFF4CAF50.toInt())
                    startListening()
                } else {
                    text = "🎙 Off"
                    setTextColor(0xFF888888.toInt())
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                }
            }
        }
        dragRow.addView(voiceBtn)

        val expandBtn = TextView(this).apply {
            text = "↗"
            textSize = 16f
            setTextColor(0xFFCEBAA2.toInt())
            setPadding(0, 0, 12, 0)
            setOnClickListener {
                startActivity(Intent(this@OverlayService, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                hideOverlay()
            }
        }
        dragRow.addView(expandBtn)

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFF888888.toInt())
            setOnClickListener { hideOverlay() }
        }
        dragRow.addView(closeBtn)
        root.addView(dragRow)

        responseText = TextView(this).apply {
            text = "Listening..."
            setTextColor(0xFFEEEEEE.toInt())
            textSize = 15f
            setPadding(0, 4, 0, 8)
        }
        root.addView(responseText)

        waveformView = WaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48)
        }
        root.addView(waveformView)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
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
            setPadding(12, 0, 0, 0)
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
            textSize = 18f
            setPadding(12, 0, 0, 0)
            setOnClickListener { startListening() }
        }
        inputRow.addView(micBtn)
        root.addView(inputRow)

        overlayParams = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        // Make draggable
        var lastX = 0f
        var lastY = 0f
        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { lastX = event.rawX; lastY = event.rawY }
                android.view.MotionEvent.ACTION_MOVE -> {
                    overlayParams.x += (event.rawX - lastX).toInt()
                    overlayParams.y -= (event.rawY - lastY).toInt()
                    lastX = event.rawX; lastY = event.rawY
                    windowManager?.updateViewLayout(root, overlayParams)
                }
            }
            true
        }

        overlayView = root
        windowManager?.addView(root, overlayParams)
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

    private val voiceReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                "com.rio.gamaentity.VOICE_RESULT" -> {
                    val transcript = intent.getStringExtra("transcript") ?: return
                    handler.post {
                        responseText.text = "You: $transcript"
                        sendToGAMA(transcript)
                    }
                }
                "com.rio.gamaentity.VOICE_PARTIAL" -> {
                    val partial = intent.getStringExtra("partial") ?: return
                    handler.post { responseText.text = partial }
                }
                "com.rio.gamaentity.VOICE_RMS" -> {
                    val rms = intent.getFloatExtra("rms", 0f)
                    handler.post { if (::waveformView.isInitialized) waveformView.updateAmplitude((rms + 10) * 300) }
                }
            }
        }
    }

    private fun startListening() {
        if (!isActive || !voiceEnabled) return
        handler.post {
            responseText.text = "Listening..."
            if (::waveformView.isInitialized) waveformView.updateAmplitude(0f)
            startActivity(Intent(this, VoiceCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            val contacts = getContactsList()
            val contactsSection = if (contacts.isNotEmpty()) "CONTACTS:\n$contacts\n" else ""
            messages.put(JSONObject().apply {
                put("role", "system")
                val appLang = getSharedPreferences("gama_prefs", android.content.Context.MODE_PRIVATE).getString("app_language", "en-ZA") ?: "en-ZA"
                val langName = mapOf("en-ZA" to "English", "zu-ZA" to "Zulu", "af-ZA" to "Afrikaans", "st-ZA" to "Sotho", "xh-ZA" to "Xhosa")[appLang] ?: "English"
                put("content", """You are GAMA, a concise AI voice assistant. User: $userName. Always respond in $langName. Be very brief.
$contactsSection
ALWAYS output the command immediately when user asks for an action. Never say "I will" or describe what you will do — just output the command. Use exact numbers from contacts:
CALL:NUMBER (regular call)
PLEASE_CALL:CONTACT_NAME:NETWORK (please call me USSD — use when user says please call, call me back, callback)
WHATSAPP:NUMBER:MESSAGE
GOOGLE:search terms
YOUTUBE:search terms (YouTube video search only)
YOUTUBE_MUSIC:song or artist (YouTube Music — use when user mentions music, song, play)
SPOTIFY:song or artist (use when user mentions Spotify)
FLASHLIGHT:ON or OFF
OPEN_APP:app name
ALARM:HH:MM:Label
Never use contact names in commands, always use their number.""")
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

                        if (ttsReady) {
                            val speakText = if (!hasCommand) reply.replace(Regex("[*_#]"), "").take(200) else ""
                            if (speakText.isNotEmpty()) {
                                tts?.speak(speakText, TextToSpeech.QUEUE_FLUSH, null, "done")
                                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                    override fun onStart(u: String?) {}
                                    override fun onDone(u: String?) { handler.postDelayed({ if (isActive && voiceEnabled) startListening() }, 400) }
                                    override fun onError(u: String?) { handler.post { if (isActive && voiceEnabled) startListening() } }
                                })
                            } else {
                                handler.postDelayed({ if (isActive && voiceEnabled) startListening() }, 800)
                            }
                        } else {
                            handler.postDelayed({ if (isActive && voiceEnabled) startListening() }, 800)
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
        // Check PLEASE_CALL first before loop to avoid CALL matching it
        if (reply.contains(Regex("(?i)PLEASE_CALL:")) || 
            reply.contains(Regex("(?i)WHATSAPP:")) ||
            reply.contains(Regex("(?i)\bCALL:"))) {
            // Hide overlay so confirmation screen is visible
            overlayView?.let { windowManager?.removeView(it) }
            overlayView = null
            startActivity(Intent(this, AssistantOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra("execute_command", reply)
                putExtra("reshow_overlay", true)
            })
            return true
        }

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

            if (!t.contains(Regex("(?i)PLEASE_CALL"))) {
                Regex("(?i)CALL:([^\n]+)").find(t)?.let {
                    startActivity(Intent(this, AssistantOverlayActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("execute_command", reply)
                    })
                    return true
                }
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



            Regex("(?i)GMAIL:(.+)").find(t)?.let {
                startActivity(Intent(this, AssistantOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("execute_command", reply)
                })
                return true
            }

            Regex("(?i)SPOTIFY:(.+)").find(t)?.let {
                val query = it.groupValues[1].trim()
                speak("Opening Spotify")
                val spotifyUri = android.net.Uri.parse("spotify:search:${Uri.encode(query)}")
                val intent = Intent(Intent.ACTION_VIEW, spotifyUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("com.spotify.music")
                }
                try { startActivity(intent) } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://open.spotify.com/search/${android.net.Uri.encode(query)}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                return true
            }

            Regex("(?i)YOUTUBE_MUSIC:(.+)").find(t)?.let {
                val query = it.groupValues[1].trim()
                speak("Opening YouTube Music")
                val ytmIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com/search?q=${android.net.Uri.encode(query)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("com.google.android.apps.youtube.music")
                }
                try { startActivity(ytmIntent) } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com/search?q=${android.net.Uri.encode(query)}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                return true
            }
        }
        return false
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun getContactsList(): String {
        val sb = StringBuilder()
        try {
            contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    sb.append("$name: $number\n")
                }
            }
        } catch (e: Exception) {}
        return sb.toString().take(3000)
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
        try { unregisterReceiver(voiceReceiver) } catch (e: Exception) {}
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        overlayView?.let { windowManager?.removeView(it) }
    }
}
