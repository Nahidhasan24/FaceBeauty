package com.facebeauty.sample

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.facebeauty.BeautyConfig
import com.facebeauty.BeautyPanelView
import com.facebeauty.BeautyParams
import com.facebeauty.livekit.BeautyCameraCapturer
import com.facebeauty.livekit.BeautyVideoProcessor
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoPreset169
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import java.util.Locale

/**
 * Runs the real LiveKit camera pipeline (capturer -> BeautyVideoProcessor -> track sink) and
 * renders the local track, so the filter can be tested without a server. The frames shown are
 * exactly the frames that would be encoded and published.
 */
class LiveKitActivity : ComponentActivity() {

    private lateinit var room: Room
    private lateinit var beauty: BeautyVideoProcessor
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var stats: TextView
    private var track: LocalVideoTrack? = null
    private var params = BeautyParams.natural()
    private var front = true
    private val main = Handler(Looper.getMainLooper())

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else finish()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val text = String.format(
                Locale.US, "LiveKit %s  cam %.0f / out %.0f fps  gpu %.1f ms\nlandmarks %.0f ms  queued %d  face %s",
                beauty.config.tier, beauty.inputFps, beauty.fps, beauty.frameMs, beauty.detectionMs,
                beauty.queuedFrames,
                if (beauty.hasFace()) "yes" else "no",
            )
            stats.text = text
            Log.i("FaceBeautyStats", text.replace('\n', ' '))
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        room = LiveKit.create(applicationContext)
        // Debug: adb shell am start -n .../.LiveKitActivity --es preset glam
        params = when (intent.getStringExtra("preset")) {
            "smooth" -> BeautyParams.smooth()
            "glow" -> BeautyParams.glow()
            "fresh" -> BeautyParams.fresh()
            "rosy" -> BeautyParams.rosyLook()
            "fair" -> BeautyParams.fair()
            "glam" -> BeautyParams.glam()
            "golden" -> BeautyParams.golden()
            "eyes" -> BeautyParams.natural().apply {
                lens = 0.8f
                lensColor = 0xFF4F86C6.toInt()
                eyelashes = 0.7f
                eyeliner = 0.6f
                darkCircles = 0.7f
                eyeBrighten = 0.6f
            }
            else -> BeautyParams.natural()
        }
        beauty = BeautyVideoProcessor(this, room.lkObjects.eglBase)
        beauty.setParams(params)

        renderer = SurfaceViewRenderer(this)
        room.initVideoRenderer(renderer)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        renderer.setMirror(true)

        stats = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x80000000.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(16, 12, 16, 12)
        }
        // The library's selector drives the video processor directly.
        val panel = BeautyPanelView(this).apply {
            setParams(this@LiveKitActivity.params)
            setListener { p -> beauty.setParams(p) }
            setCompareListener { original -> beauty.isEnabled = !original }
        }
        val flip = Button(this).apply {
            text = "Flip"
            isAllCaps = false
            setOnClickListener {
                front = !front
                track?.switchCamera(position = if (front) CameraPosition.FRONT else CameraPosition.BACK)
                renderer.setMirror(front)
            }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(flip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.END
                marginEnd = 24
            })
            addView(panel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            setPadding(0, 0, 0, 32)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(renderer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(stats, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(24, 48, 0, 0) })
            addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val preset = if (beauty.config.tier == BeautyConfig.Tier.LOW) VideoPreset169.H540 else VideoPreset169.H720
        val options = LocalVideoTrackOptions(position = CameraPosition.FRONT, captureParams = preset.capture)
        // BeautyCameraCapturer keeps >= 20 fps in dim rooms; "--es capturer webrtc" compares
        // against LiveKit's stock capturer.
        track = if (intent.getStringExtra("capturer") == "webrtc") {
            room.localParticipant.createVideoTrack("camera", options, beauty)
        } else {
            room.localParticipant.createVideoTrack("camera", BeautyCameraCapturer.create(this, true), options, beauty)
        }.also {
            it.startCapture()
            it.addRenderer(renderer)
        }
    }

    override fun onResume() {
        super.onResume()
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        track?.let {
            it.removeRenderer(renderer)
            it.stopCapture()
            it.dispose()
        }
        beauty.dispose()
        renderer.release()
        room.release()
        super.onDestroy()
    }
}
