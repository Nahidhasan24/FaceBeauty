package com.facebeauty.sample

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.facebeauty.BeautyConfig
import com.facebeauty.FaceTracker
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Landmark-model benchmark without camera or rendering:
 * adb push face.png /data/local/tmp/ && adb shell run-as com.facebeauty.sample cp /data/local/tmp/face.png files/
 * adb shell am start -n com.facebeauty.sample/.BenchActivity --ei faces 1 --ez gpu false --ei size 416
 */
class BenchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { textSize = 16f; setPadding(32, 96, 32, 32) }
        setContentView(text)

        val config = BeautyConfig.forDevice(this).apply {
            maxFaces = intent.getIntExtra("faces", maxFaces)
            useGpuDelegate = intent.getBooleanExtra("gpu", false)
        }
        val size = intent.getIntExtra("size", config.detectionSize)
        thread(name = "bench") {
            val src = BitmapFactory.decodeFile(File(filesDir, "face.png").path)
            val s = size / maxOf(src.width, src.height).toFloat()
            val bmp = Bitmap.createScaledBitmap(src, (src.width * s).toInt() and 1.inv(), (src.height * s).toInt() and 1.inv(), true)
            val buf = ByteBuffer.allocateDirect(bmp.byteCount).order(ByteOrder.nativeOrder())
            bmp.copyPixelsToBuffer(buf)
            val tracker = FaceTracker(this, config)
            while (!tracker.isReady) SystemClock.sleep(10)
            val times = ArrayList<Float>()
            repeat(60) { i ->
                tracker.submit(buf, bmp.width, bmp.height, SystemClock.elapsedRealtimeNanos())
                while (!tracker.isIdle) SystemClock.sleep(1)
                if (i >= 10) times += tracker.detectMs
            }
            times.sort()
            val msg = "faces=${config.maxFaces} gpu=${config.useGpuDelegate} ${bmp.width}x${bmp.height} " +
                "found=${tracker.latest.faces.size} median=${times[times.size / 2]} min=${times.first()} max=${times.last()}"
            Log.i("FaceBeautyBench", msg)
            runOnUiThread { text.text = msg }
            tracker.close()
        }
    }
}
