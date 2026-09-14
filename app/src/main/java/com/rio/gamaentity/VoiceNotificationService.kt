package com.rio.gamaentity

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class VoiceNotificationService : Service() {

    private val CHANNEL_ID = "gama_voice_channel"
    private val NOTIF_ID = 1001
    private var isListening = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsEngine: TextToSpeech? = null
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())
    private val messages = JSONArray()
    private var systemPromptAdded = false
    private var currentChatFile = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        const val ACTION_START_LISTENING = "START_LISTENING"
        const val ACTION_STOP_LISTENING = "STOP_LISTENING"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(false))
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        currentChatFile = "notif_chat_$timestamp.json"
        ttsEngine = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                startForeground(NOTIF_ID, buildNotification(true))
                if (!isListening) handler.post { startListening() }
            }
            ACTION_STOP_LISTENING -> {
                stopListening()
                startForeground(NOTIF_ID, buildNotification(false))
            }
            ACTION_STOP_SERVICE -> {
                stopListening()
                ttsEngine?.stop()
                ttsEngine?.shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startForeground(NOTIF_ID, buildNotification(false))
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "GAMA Voice Assistant",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "GAMA Entity voice assistant"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(listening: Boolean): Notification {
        val startPending = PendingIntent.getService(this, 0,
            Intent(this, VoiceNotificationService::class.java).apply { action = ACTION_START_LISTENING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPending = PendingIntent.getService(this, 1,
            Intent(this, VoiceNotificationService::class.java).apply { action = ACTION_STOP_LISTENING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismissPending = PendingIntent.getService(this, 2,
            Intent(this, VoiceNotificationService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openApp = PendingIntent.getActivity(this, 3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GAMA Entity")
            .setContentText(if (listening) "🎙 Listening..." else "Tap mic to speak")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_btn_speak_now,
                if (listening) "Stop" else "Speak",
                if (listening) stopPending else startPending)
            .addAction(android.R.drawable.ic_delete, "✕ Turn Off", dismissPending)
            .build()
    }

    private fun updateNotificationText(text: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GAMA Entity")
            .setContentText(text.take(80))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun startListening() {
        if (!isListening) {
            isListening = true
            startForeground(NOTIF_ID, buildNotification(true))
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull() ?: run {
                    if (isListening) handler.postDelayed({ startListening() }, 300)
                    return
                }
                if (transcript.isNotEmpty() && isListening) {
                    updateNotificationText("You: $transcript")
                    sendToGAMA(transcript)
                }
            }
            override fun onError(error: Int) {
                if (isListening) handler.postDelayed({ startListening() }, 500)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        isListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun sendToGAMA(transcript: String) {
        val prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)
        val groqKey = prefs.getString("groq_key", "") ?: ""
        val userName = prefs.getString("user_name", "User") ?: "User"
        if (groqKey.isEmpty()) { if (isListening) handler.post { startListening() }; return }

        if (!systemPromptAdded) {
            val sys = JSONObject()
            sys.put("role", "system")
            sys.put("content", buildSystemPrompt(userName))
            messages.put(sys)
            systemPromptAdded = true
        }
        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", transcript)
        messages.put(userMsg)
        saveChat(transcript, "user")

        val body = JSONObject()
        body.put("model", "openai/gpt-oss-120b")
        body.put("messages", messages)
        body.put("max_tokens", 500)
        body.put("tool_choice", "none")

        client.newCall(Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $groqKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { if (isListening) startListening() }
            }
            override fun onResponse(call: Call, response: Response) {
                val b = response.body?.string()
                handler.post {
                    try {
                        val json = JSONObject(b ?: "")
                        if (json.has("error")) {
                            if (messages.length() > 0) messages.remove(messages.length() - 1)
                            if (isListening) startListening()
                            return@post
                        }
                        val reply = json.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content")
                            .replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()

                        val assistantMsg = JSONObject()
                        assistantMsg.put("role", "assistant")
                        assistantMsg.put("content", reply)
                        messages.put(assistantMsg)
                        saveChat(reply, "assistant")
                        updateNotificationText("GAMA: ${reply.take(60)}")
                        handleAction(reply)

                        if (ttsReady) {
                            val clean = reply.replace(Regex("[*_#]"), "").take(300)
                            ttsEngine?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "done")
                            ttsEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(u: String?) {}
                                override fun onDone(u: String?) {
                                    handler.postDelayed({ if (isListening) startListening() }, 500)
                                }
                                override fun onError(u: String?) {
                                    handler.post { if (isListening) startListening() }
                                }
                            })
                        } else {
                            handler.postDelayed({ if (isListening) startListening() }, 1000)
                        }
                    } catch (e: Exception) {
                        if (isListening) startListening()
                    }
                }
            }
        })
    }

    private fun saveChat(content: String, role: String) {
        try {
            val chatsDir = File(filesDir, "chats")
            if (!chatsDir.exists()) chatsDir.mkdirs()
            val file = File(chatsDir, currentChatFile)
            val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            val msg = JSONObject()
            msg.put("role", role)
            msg.put("content", content)
            arr.put(msg)
            file.writeText(arr.toString())
        } catch (e: Exception) {}
    }

    private fun handleAction(reply: String) {
        for (line in reply.split("\n")) {
            val t = line.trim()
            Regex("(?i)CALL:([^\\n]+)").find(t)?.let {
                val number = lookupContact(it.groupValues[1].trim())
                try { startActivity(Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:$number")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
                return
            }
            Regex("(?i)FLASHLIGHT:(ON|OFF)").find(t)?.let {
                try {
                    val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    cm.setTorchMode(cm.cameraIdList[0], it.groupValues[1].uppercase() == "ON")
                } catch (e: Exception) {}
                return
            }
            Regex("(?i)GOOGLE:(.+)").find(t)?.let {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode(it.groupValues[1].trim())}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                return
            }
            Regex("(?i)YOUTUBE:(.+)").find(t)?.let {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=${android.net.Uri.encode(it.groupValues[1].trim())}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                return
            }
            Regex("(?i)OPEN_APP:(.+)").find(t)?.let {
                val appName = it.groupValues[1].trim().lowercase()
                val found = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .firstOrNull { ri -> ri.loadLabel(packageManager).toString().lowercase().contains(appName) }
                found?.let { ri -> packageManager.getLaunchIntentForPackage(ri.activityInfo.packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }?.let { startActivity(it) } }
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
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {}
                return
            }
        }
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

    private fun buildSystemPrompt(userName: String): String {
        return """You are GAMA, an AI voice agent. User: $userName. Be very concise.
Only output a command when explicitly asked:
CALL:NUMBER
FLASHLIGHT:ON or OFF
GOOGLE:search terms
YOUTUBE:search terms
OPEN_APP:app name
ALARM:HH:MM:Label"""
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopListening()
        ttsEngine?.stop()
        ttsEngine?.shutdown()
    }
}
