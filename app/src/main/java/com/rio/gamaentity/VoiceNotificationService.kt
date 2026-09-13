package com.rio.gamaentity

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class VoiceNotificationService : Service() {

    private val CHANNEL_ID = "gama_voice_channel"
    private val NOTIF_ID = 1001
    private var isListening = false
    private var audioRecord: AudioRecord? = null
    private val handler = Handler(Looper.getMainLooper())
    private val messages = JSONArray()
    private var systemPromptAdded = false

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                startForeground(NOTIF_ID, buildNotification(listening = true))
                if (!isListening) startListening()
            }
            ACTION_STOP_LISTENING -> {
                stopListening()
                startForeground(NOTIF_ID, buildNotification(listening = false))
            }
            ACTION_STOP_SERVICE -> {
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification(listening = false))
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "GAMA Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "GAMA Entity voice assistant" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(listening: Boolean): Notification {
        val startIntent = Intent(this, VoiceNotificationService::class.java).apply { action = ACTION_START_LISTENING }
        val stopIntent = Intent(this, VoiceNotificationService::class.java).apply { action = ACTION_STOP_LISTENING }
        val dismissIntent = Intent(this, VoiceNotificationService::class.java).apply { action = ACTION_STOP_SERVICE }

        val startPending = PendingIntent.getService(this, 0, startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismissPending = PendingIntent.getService(this, 2, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val openApp = PendingIntent.getActivity(this, 3,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GAMA Entity")
            .setContentText(if (listening) "Listening..." else "Tap mic to speak")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_btn_speak_now, if (listening) "Listening" else "Speak",
                if (listening) stopPending else startPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .addAction(android.R.drawable.ic_delete, "Dismiss", dismissPending)
            .build()
    }

    private fun startListening() {
        isListening = true
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        audioRecord?.startRecording()

        Thread {
            val buffer = ShortArray(bufferSize / 2)
            val audioData = ByteArrayOutputStream()
            var silenceCount = 0
            val maxSilenceFrames = 45
            var hasSpoken = false
            var ambientSum = 0.0
            var ambientCount = 0
            var ambientThreshold = 1200.0

            while (isListening) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val rms = Math.sqrt(buffer.take(read).map { it.toLong() * it }.sum().toDouble() / read)
                    if (ambientCount < 10) {
                        ambientSum += rms
                        ambientCount++
                        if (ambientCount == 10) {
                            ambientThreshold = (ambientSum / 10) * 3
                            ambientThreshold = ambientThreshold.coerceIn(800.0, 2500.0)
                        }
                    }
                    val speechThreshold = if (hasSpoken) ambientThreshold else ambientThreshold * 1.5
                    if (rms > speechThreshold) {
                        hasSpoken = true
                        silenceCount = 0
                    } else if (hasSpoken) {
                        silenceCount++
                    }
                    val byteBuffer = java.nio.ByteBuffer.allocate(read * 2)
                    byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    buffer.take(read).forEach { byteBuffer.putShort(it) }
                    audioData.write(byteBuffer.array())

                    if (hasSpoken && silenceCount > maxSilenceFrames) {
                        isListening = false
                        val pcm = audioData.toByteArray()
                        audioData.reset()
                        sendToWhisper(pcm, sampleRate)
                    }
                }
            }
        }.start()
    }

    private fun stopListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun sendToWhisper(audioBytes: ByteArray, sampleRate: Int) {
        val prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)
        val groqKey = prefs.getString("groq_key", "") ?: ""
        if (groqKey.isEmpty()) return

        val wavBytes = createWavFile(audioBytes, sampleRate)
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wavBytes.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("language", "en")
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $groqKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { if (isRunning) resumeListening() }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                try {
                    val transcript = JSONObject(body ?: "").getString("text").trim()
                    if (transcript.isNotEmpty()) sendToGama(transcript, groqKey)
                    else handler.post { resumeListening() }
                } catch (e: Exception) {
                    handler.post { resumeListening() }
                }
            }
        })
    }

    private fun sendToGama(transcript: String, groqKey: String) {
        updateNotification("Thinking: $transcript")

        if (!systemPromptAdded) {
            val sys = JSONObject()
            sys.put("role", "system")
            sys.put("content", buildSystemPrompt())
            messages.put(sys)
            systemPromptAdded = true
        }
        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", transcript)
        messages.put(userMsg)

        val body = JSONObject()
        body.put("model", "openai/gpt-oss-120b")
        body.put("messages", messages)
        body.put("max_tokens", 1000)
        body.put("tool_choice", "none")

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $groqKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { resumeListening() }
            }
            override fun onResponse(call: Call, response: Response) {
                val b = response.body?.string()
                try {
                    val json = JSONObject(b ?: "")
                    if (json.has("error")) {
                        if (messages.length() > 0) messages.remove(messages.length() - 1)
                        handler.post { resumeListening() }
                        return
                    }
                    val reply = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                        .replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()

                    val assistantMsg = JSONObject()
                    assistantMsg.put("role", "assistant")
                    assistantMsg.put("content", reply)
                    messages.put(assistantMsg)

                    updateNotification(reply.take(80))
                    handleActionNoConfirmation(reply)

                    var ttsEngine: android.speech.tts.TextToSpeech? = null
                    ttsEngine = android.speech.tts.TextToSpeech(this@VoiceNotificationService) { status ->
                        if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                            val clean = reply.replace(Regex("[*_#]"), "").take(300)
                            ttsEngine?.speak(clean, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "notif_done")
                        }
                    }
                    handler.postDelayed({ resumeListening() }, 3000)
                } catch (e: Exception) {
                    handler.post { resumeListening() }
                }
            }
        })
    }

    private fun resumeListening() {
        if (isRunning) {
            startListening()
            startForeground(NOTIF_ID, buildNotification(listening = true))
        }
    }

    private fun updateNotification(text: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GAMA Entity")
            .setContentText(text.take(80))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun handleActionNoConfirmation(reply: String) {
        for (line in reply.split("\n")) {
            val t = line.trim()

            Regex("(?i)CALL:([^\\n]+)").find(t)?.let {
                val number = lookupContact(it.groupValues[1].trim())
                val intent = Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { startActivity(intent) } catch (e: Exception) {}
                return
            }

            Regex("(?i)GOOGLE:(.+)").find(t)?.let {
                val intent = Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode(it.groupValues[1].trim())}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            }

            Regex("(?i)YOUTUBE:(.+)").find(t)?.let {
                val intent = Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.youtube.com/results?search_query=${android.net.Uri.encode(it.groupValues[1].trim())}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            }

            Regex("(?i)OPEN_APP:(.+)").find(t)?.let {
                val appName = it.groupValues[1].trim().lowercase()
                val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val found = packageManager.queryIntentActivities(mainIntent, 0)
                    .firstOrNull { ri -> ri.loadLabel(packageManager).toString().lowercase().contains(appName) }
                if (found != null) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(found.activityInfo.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (launchIntent != null) startActivity(launchIntent)
                }
                return
            }

            Regex("(?i)FLASHLIGHT:(ON|OFF)").find(t)?.let {
                val state = it.groupValues[1].uppercase()
                try {
                    val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    cm.setTorchMode(cm.cameraIdList[0], state == "ON")
                } catch (e: Exception) {}
                return
            }

            Regex("(?i)ALARM:(\\d{1,2}):(\\d{2})(?::(.+))?").find(t)?.let {
                val hour = it.groupValues[1].toIntOrNull() ?: return
                val minute = it.groupValues[2].toIntOrNull() ?: return
                val label = it.groupValues[3].ifEmpty { "GAMA Alarm" }
                val alarmIntent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                    putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { startActivity(alarmIntent) } catch (e: Exception) {}
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

    private fun buildSystemPrompt(): String {
        val prefs = getSharedPreferences("gama_prefs", MODE_PRIVATE)
        val userName = prefs.getString("user_name", "User") ?: "User"
        return """You are GAMA, an AI agent. User: $userName. Be concise.
CALL:NUMBER
GOOGLE:search terms
YOUTUBE:search terms
OPEN_APP:app name
FLASHLIGHT:ON or OFF
ALARM:HH:MM:Label
Only output commands when explicitly asked."""
    }

    private fun createWavFile(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2
        val out = ByteArrayOutputStream()
        val header = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            (totalDataLen and 0xff).toByte(), (totalDataLen shr 8 and 0xff).toByte(),
            (totalDataLen shr 16 and 0xff).toByte(), (totalDataLen shr 24 and 0xff).toByte(),
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
            16, 0, 0, 0, 1, 0, 1, 0,
            (sampleRate and 0xff).toByte(), (sampleRate shr 8 and 0xff).toByte(),
            (sampleRate shr 16 and 0xff).toByte(), (sampleRate shr 24 and 0xff).toByte(),
            (byteRate and 0xff).toByte(), (byteRate shr 8 and 0xff).toByte(),
            (byteRate shr 16 and 0xff).toByte(), (byteRate shr 24 and 0xff).toByte(),
            2, 0, 16, 0,
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            (pcmData.size and 0xff).toByte(), (pcmData.size shr 8 and 0xff).toByte(),
            (pcmData.size shr 16 and 0xff).toByte(), (pcmData.size shr 24 and 0xff).toByte()
        )
        out.write(header)
        out.write(pcmData)
        return out.toByteArray()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopListening()
    }
}
