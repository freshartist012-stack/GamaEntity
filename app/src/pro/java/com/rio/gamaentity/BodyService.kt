package com.rio.gamaentity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class BodyService : LifecycleService() {
    private class Pt(val t: Long, val x: Float, val y: Float)

    private var landmarker: PoseLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var started = false
    private var lastFrame = 0L
    private val trail = ArrayList<Pt>()
    private var side = 0
    private var lastSwipe = 0L
    private var lastDir = ""

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel("body", "Body mode", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "body")
            .setContentTitle("GAMA body mode is on")
            .setContentText("Raise a hand and wave to swipe. Turn off in the GAMA menu")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(80, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) else startForeground(80, n)
        if (!started) begin()
        return START_STICKY
    }

    private fun begin() {
        started = true
        try {
            val bytes = BodyModel.file(this).readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes)
            buf.rewind()
            val base = BaseOptions.builder().setModelAssetBuffer(buf).build()
            val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.4f)
                .setMinPosePresenceConfidence(0.4f)
                .setMinTrackingConfidence(0.4f)
                .build()
            landmarker = PoseLandmarker.createFromOptions(this, opts)
        } catch (e: Exception) {
            android.util.Log.e("GAMA_BODY", "model failed: " + e.message)
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
                    .setTargetResolution(android.util.Size(640, 480))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(exec) { img -> analyze(img) }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                android.util.Log.e("GAMA_BODY", "camera failed: " + e.message)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrame < 40) return
            lastFrame = now
            val bmp = img.toBitmap()
            val m = Matrix()
            m.postRotate(img.imageInfo.rotationDegrees.toFloat())
            val rot = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val result = landmarker?.detectForVideo(BitmapImageBuilder(rot).build(), now)
            val pose = result?.landmarks()?.firstOrNull()
            if (pose == null || pose.size < 17) {
                trail.clear()
                side = 0
                return
            }
            onPose(pose, now)
        } catch (e: Exception) {
        } finally {
            img.close()
        }
    }

    private val HIGH = 2.2f
    private val LOW = 0.9f
    private val DIST = 0.55f
    private val STRAIGHT = 0.72f
    private val DOM = 1.6f
    private val OPP = 1.3f
    private var hasS = false
    private var sx = 0f
    private var sy = 0f
    private var prevT = 0L
    private var inStroke = false
    private var waitRest = false
    private var strokeStart = 0L
    private var startX = 0f
    private var startY = 0f
    private var pathLen = 0f
    private var peak = 0f
    private var lastPeak = 0f
    private var lastFired = 0L
    private var lastDirSwipe = ""

    private fun resetStroke() {
        hasS = false
        inStroke = false
        waitRest = false
    }

    private fun oppositeOf(d: String): String {
        return when (d) { "R" -> "L"; "L" -> "R"; "U" -> "D"; "D" -> "U"; else -> "" }
    }

    private fun onPose(pose: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, now: Long) {
        if (now - prevT > 300) resetStroke()
        val ls = pose[11]
        val rs = pose[12]
        val lw = pose[15]
        val rw = pose[16]
        val sdx = ls.x() - rs.x()
        val sdy = ls.y() - rs.y()
        val sw = sqrt(sdx * sdx + sdy * sdy)
        if (sw < 0.05f) { resetStroke(); return }
        val ang = atan2(sdy, sdx)
        val shoulderY = (ls.y() + rs.y()) / 2f
        val limit = shoulderY + 0.9f * sw
        val lUp = lw.y() < limit
        val rUp = rw.y() < limit
        if (!lUp && !rUp) { resetStroke(); return }
        val useLeft = if (lUp && rUp) lw.y() < rw.y() else lUp
        val s = if (useLeft) 1 else 2
        if (s != side) { side = s; resetStroke() }
        val w = if (useLeft) lw else rw
        val sh = if (useLeft) ls else rs
        val vx = w.x() - sh.x()
        val vy = w.y() - sh.y()
        val cs = cos(ang)
        val sn = sin(ang)
        val rx = -(vx * cs + vy * sn) / sw
        val ry = (-vx * sn + vy * cs) / sw
        if (!hasS) { sx = rx; sy = ry; hasS = true; prevT = now; return }
        val px = sx
        val py = sy
        sx += (rx - sx) * 0.6f
        sy += (ry - sy) * 0.6f
        val dt = (now - prevT) / 1000f
        prevT = now
        if (dt <= 0f) return
        val step = sqrt((sx - px) * (sx - px) + (sy - py) * (sy - py))
        val speed = step / dt
        if (waitRest) {
            if (speed < LOW) waitRest = false
            return
        }
        if (!inStroke) {
            if (speed > HIGH) {
                inStroke = true
                strokeStart = now
                startX = px
                startY = py
                pathLen = step
                peak = speed
            }
            return
        }
        pathLen += step
        if (speed > peak) peak = speed
        if (now - strokeStart > 600 || (speed < LOW && now - strokeStart > 60)) { inStroke = false; return }
        val dx = sx - startX
        val dy = sy - startY
        val ax = abs(dx)
        val ay = abs(dy)
        val net = sqrt(dx * dx + dy * dy)
        if (net < DIST || pathLen <= 0f || net / pathLen < STRAIGHT) return
        val horizontal = ax > ay * DOM
        val vertical = ay > ax * DOM
        if (!horizontal && !vertical) return
        val dir = if (horizontal) { if (dx > 0) "R" else "L" } else { if (dy < 0) "U" else "D" }
        inStroke = false
        waitRest = true
        if (now - lastFired < 350) return
        if (lastDirSwipe.isNotEmpty() && dir == oppositeOf(lastDirSwipe) && now - lastFired < 1000 && peak < lastPeak * OPP) return
        lastFired = now
        lastPeak = peak
        lastDirSwipe = dir
        val cmd = when (dir) { "R" -> "SCROLL:RIGHT"; "L" -> "SCROLL:LEFT"; "U" -> "SCROLL:DOWN"; else -> "SCROLL:UP" }
        main.post { GamaAccessibilityService.executeAction(cmd) }
    }

    override fun onDestroy() {
        try { provider?.unbindAll() } catch (e: Exception) {}
        try { landmarker?.close() } catch (e: Exception) {}
        exec.shutdown()
        started = false
        super.onDestroy()
    }
}
