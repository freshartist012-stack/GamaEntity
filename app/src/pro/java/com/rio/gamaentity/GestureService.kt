package com.rio.gamaentity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Matrix
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class GestureService : LifecycleService() {
    private var recognizer: GestureRecognizer? = null
    private var provider: ProcessCameraProvider? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastFrame = 0L
    private var current = ""
    private var heldSince = 0L
    private var firedFor = ""
    private var lastAction = 0L
    @Volatile private var started = false
    private var frames = 0
    private var lastToast = 0L
    private var lastErr = ""
    private var dbgHand = false
    private var dbgName = ""

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel("gesture", "Gestures", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "gesture")
            .setContentTitle("GAMA is watching for gestures")
            .setContentText("Turn off in the GAMA menu")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(78, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) else startForeground(78, n)
        if (!started) begin()
        return START_STICKY
    }

    private fun begin() {
        started = true
        try {
            val bytes = GestureModel.file(this).readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes)
            buf.rewind()
            val base = BaseOptions.builder().setModelAssetBuffer(buf).build()
            val opts = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .build()
            recognizer = GestureRecognizer.createFromOptions(this, opts)
        } catch (e: Exception) {
            android.util.Log.e("GAMA_GESTURE", "model failed: " + e.message)
            stopSelf()
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(exec) { img -> analyze(img) }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                android.util.Log.e("GAMA_GESTURE", "camera failed: " + e.message)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrame < 120) return
            lastFrame = now
            val bmp = img.toBitmap()
            val m = Matrix()
            m.postRotate(img.imageInfo.rotationDegrees.toFloat())
            val rot = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val result = recognizer?.recognize(BitmapImageBuilder(rot).build())
            val name = result?.gestures()?.firstOrNull()?.firstOrNull()?.categoryName() ?: ""
            dbgHand = result?.landmarks()?.isNotEmpty() == true
            dbgName = name
            onGesture(name, now)
        } catch (e: Exception) {
            lastErr = (e.message ?: "error").take(60)
        } finally {
            frames++
            debugTick()
            img.close()
        }
    }

    private fun debugTick() {
        val now = System.currentTimeMillis()
        if (now - lastToast < 3000) return
        lastToast = now
        val msg = "Gesture debug: frames=" + frames + " hand=" + dbgHand + " gesture=" + dbgName + (if (lastErr.isNotEmpty()) " err=" + lastErr else "")
        main.post { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun onGesture(name: String, now: Long) {
        val act = when (name) {
            "Victory" -> "wake"
            "Closed_Fist" -> "pause"
            "Thumb_Up" -> "up"
            "Thumb_Down" -> "down"
            else -> ""
        }
        if (act.isEmpty()) { current = ""; firedFor = ""; return }
        if (act != current) { current = act; heldSince = now; return }
        if (act == firedFor) return
        if (now - heldSince < 900) return
        if (now - lastAction < 2000) return
        firedFor = act
        lastAction = now
        main.post { doAction(act) }
    }

    private fun doAction(act: String) {
        if (act == "wake") {
            startService(Intent(this, ProOverlayService::class.java).apply { action = OverlayService.ACTION_SHOW })
        } else if (act == "pause") {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        } else if (act == "up") {
            GamaAccessibilityService.executeAction("SCROLL:UP")
        } else if (act == "down") {
            GamaAccessibilityService.executeAction("SCROLL:DOWN")
        }
    }

    override fun onDestroy() {
        try { provider?.unbindAll() } catch (e: Exception) {}
        try { recognizer?.close() } catch (e: Exception) {}
        exec.shutdown()
        started = false
        super.onDestroy()
    }
}
