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
        startForeground(NOTIF_ID, buildNotification(false, "Tap mic to speak"))
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        currentChatFile = "notif_chat_$timestamp.json"
        ttsEngine = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                isListening = true
                updateNotification(true, "Listening...")
                handler.post { startListening() }
            }
            ACTION_STOP_LISTENING -> {
                isListening = false
                speechRecognizer?.destroy()
                speechRecognizer = null
                updateNotification(false, "Tap mic to speak")
            }
            ACTION_STOP_SERVICE -> {
                isListening = false
                speechRecognizer?.destroy()
                ttsEngine?.stop()
                ttsEngine?.shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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

    private fun buildNotification(listening: Boolean, statusText: String): Notification {
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
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_btn_speak_now,
                if (listening) "⏹ Stop" else "🎙 Speak",
                if (listening) stopPending else startPending)
            .addAction(android.R.drawable.ic_delete, "✕ Off", dismissPending)
            .build()
    }

    private fun updateNotification(listening: Boolean, text: String) {
        val notif = buildNotification(listening, text)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
        if (listening) startForeground(NOTIF_ID, notif)
    }

    private fun startListening() {
        if (!isListening) return
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull()
                if (!transcript.isNullOrEmpty() && isListening) {
                    updateNotification(true, "You: $transcript")
                    sendToGAMA(transcript)
                } else if (isListening) {
                    handler.postDelayed({ startListening() }, 300)
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
        speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        })
    }

    private fun sendToGAMA(transcript: String) {
        val prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)
        val groqKey = prefs.getString("groq_key", "") ?: ""
        val userName = prefs.getString("user_name", "User") ?: "User"
        if (groqKey.isEmpty()) { if (isListening) handler.post { startListening() }; return }

        if (!systemPromptAdded) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", buildSystemPrompt(userName))
            })
            systemPromptAdded = true
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", transcript)
        })
        saveChat(transcript, "user")

        client.newCall(Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $groqKey")
            .addHeader("Content-Type", "application/json")
            .post(JSONObject().apply {
                put("model", "openai/gpt-oss-120b")
                put("messages", messages)
                put("max_tokens", 500)
                put("tool_choice", "none")
            }.toString().toRequestBody("application/json".toMediaType())).build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (messages.length() > 0) messages.remove(messages.length() - 1)
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

                        messages.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", reply)
                        })
                        saveChat(reply, "assistant")
                        updateNotification(true, "GAMA: ${reply.take(60)}")
                        handleAction(reply)

                        if (ttsReady) {
                            ttsEngine?.speak(reply.replace(Regex("[*_#]"), "").take(300),
                                TextToSpeech.QUEUE_FLUSH, null, "done")
                            ttsEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(u: String?) {}
                                override fun onDone(u: String?) {
                                    handler.postDelayed({
                                        if (isListening) {
                                            updateNotification(true, "Listening...")
                                            startListening()
                                        }
                                    }, 500)
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

    private fun handleAction(reply: String) {
        for (line in reply.split("\n")) {
            val t = line.trim()

            Regex("(?i)WHATSAPP:([^:]+):(.+)").find(t)?.let {
                val number = lookupContact(it.groupValues[1].trim())
                val message = it.groupValues[2].trim()
                val uri = android.net.Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${android.net.Uri.encode(message)}")
                try { startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }) } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                return
            }

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
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode(it.groupValues[1].trim())}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                return
            }

            Regex("(?i)YOUTUBE:(.+)").find(t)?.let {
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.youtube.com/results?search_query=${android.net.Uri.encode(it.groupValues[1].trim())}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                return
            }

            Regex("(?i)OPEN_APP:(.+)").find(t)?.let {
                val appName = it.groupValues[1].trim().lowercase()
                val found = packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .firstOrNull { ri -> ri.loadLabel(packageManager).toString().lowercase().contains(appName) }
                found?.let { ri ->
                    packageManager.getLaunchIntentForPackage(ri.activityInfo.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }?.let { startActivity(it) }
                }
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

            Regex("(?i)YOUTUBE_MUSIC:(.+)").find(t)?.let {
                val query = it.groupValues[1].trim()
                try {
                    startActivity(Intent(Intent.ACTION_SEARCH).apply {
                        setPackage("com.google.android.apps.youtube.music")
                        putExtra("query", query)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://music.youtube.com/search?q=${android.net.Uri.encode(query)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
                return
            }

            Regex("(?i)SPOTIFY:(.+)").find(t)?.let {
                val query = it.groupValues[1].trim()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("spotify:search:${android.net.Uri.encode(query)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://open.spotify.com/search/${android.net.Uri.encode(query)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
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

    private fun getContacts(): String {
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

    private fun buildSystemPrompt(userName: String): String {
        val contacts = getContacts()
        val contactsSection = if (contacts.isNotEmpty()) "CONTACTS:\n$contacts\n" else ""
        return """You are GAMA, an AI voice agent on Android. User: $userName. Be concise and natural.
$contactsSection
Only output a command when explicitly asked. Commands:
WHATSAPP:NUMBER:MESSAGE
CALL:NUMBER
FLASHLIGHT:ON or FLASHLIGHT:OFF
GOOGLE:search terms
YOUTUBE:search terms
YOUTUBE_MUSIC:song or artist
SPOTIFY:song or artist
OPEN_APP:app name
ALARM:HH:MM:Label
Always use actual phone numbers from contacts, never contact names."""
    }

    private fun saveChat(content: String, role: String) {
        try {
            val chatsDir = File(filesDir, "chats")
            if (!chatsDir.exists()) chatsDir.mkdirs()
            val file = File(chatsDir, currentChatFile)
            val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            arr.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
            file.writeText(arr.toString())
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isListening = false
        speechRecognizer?.destroy()
        ttsEngine?.stop()
        ttsEngine?.shutdown()
    }
}
