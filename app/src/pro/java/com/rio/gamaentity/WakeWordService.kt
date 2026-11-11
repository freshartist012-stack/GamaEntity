package com.rio.gamaentity

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.voxrt.sdk.wakeword.VoxrtWakeWordEngine

class WakeWordService : Service() {
    @Volatile private var running = false
    @Volatile private var alive = true
    private var worker: Thread? = null
    private var engine: VoxrtWakeWordEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        alive = true
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel("wake", "Wake word", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "wake")
            .setContentTitle("GAMA is listening")
            .setContentText("Say Hey Assistant")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(77, n)
        if (!running) startListening()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        running = true
        worker = Thread {
            try {
                if (engine == null) engine = VoxrtWakeWordEngine.fromAssetBytes(assets, "voxrt_wake_word.vxrt")
                val e = engine ?: return@Thread
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200) * 2)
                rec.startRecording()
                val buf = ShortArray(1600)
                var woke = false
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val hits = e.processPcm(buf.copyOf(n))
                    if (hits.isNotEmpty()) { woke = true; break }
                }
                rec.stop()
                rec.release()
                if (woke) onWake()
            } catch (ex: Exception) {
                android.util.Log.e("GAMA_WAKE", "wake failed: " + ex.message)
            }
        }
        worker?.start()
    }

    private fun onWake() {
        running = false
        engine?.reset()
        startService(Intent(this, ProOverlayService::class.java).apply { action = OverlayService.ACTION_SHOW })
        Handler(Looper.getMainLooper()).postDelayed({ if (alive && !running) startListening() }, 15000)
    }

    override fun onDestroy() {
        alive = false
        running = false
        try { worker?.join(800) } catch (e: Exception) {}
        engine?.close()
        engine = null
        super.onDestroy()
    }
}
