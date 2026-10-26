package com.facebeauty.sample

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.facebeauty.BeautyCatalog
import com.facebeauty.BeautyConfig
import com.facebeauty.BeautyImageProcessor
import com.facebeauty.BeautyParams
import java.io.File
import kotlin.concurrent.thread

/**
 * Offline renderer for README media. Push frames to files/demo/<clip>/NNN.png, then
 * adb shell am start -n com.facebeauty.sample/.DemoRenderActivity
 * Outputs files/out/<clip>/<look>/NNN.jpg for every look below.
 */
class DemoRenderActivity : ComponentActivity() {

    private val looks: Map<String, () -> BeautyParams> = linkedMapOf(
        "natural" to { BeautyParams.natural() },
        "white" to {
            BeautyParams.natural().apply {
                smoothing = 0.6f
                whitening = 0.55f
                BeautyCatalog.find("look_white")!!.set(this, 0.9f)
            }
        },
        "golden" to { BeautyParams.golden() },
        "glam" to { BeautyParams.glam() },
        "lips" to {
            BeautyParams.natural().apply {
                lipstick = 0.7f
                lipColor = 0xFFC62828.toInt()
                lipGloss = 0.5f
            }
        },
        "eyes" to {
            BeautyParams.natural().apply {
                lens = 0.75f
                lensColor = 0xFF4F86C6.toInt()
                eyelashes = 0.7f
                eyeliner = 0.6f
                eyeshadow = 0.35f
                eyeBrighten = 0.6f
                darkCircles = 0.6f
            }
        },
        "reshape" to {
            BeautyParams.none().apply {
                eyeEnlarge = 0.6f
                chinSlim = 0.6f
                faceSlim = 0.6f
                faceSmall = 0.4f
                noseSlim = 0.5f
            }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { textSize = 16f; setPadding(32, 96, 32, 32) }
        setContentView(text)
        thread(name = "demo-render") {
            // Optional filters: --es clips 4756,4535 --es looks eyes,glam
            val onlyClips = intent.getStringExtra("clips")?.split(",")?.toSet()
            val onlyLooks = intent.getStringExtra("looks")?.split(",")?.toSet()
            val root = File(filesDir, "demo")
            val clips = root.listFiles()?.filter { it.isDirectory && (onlyClips == null || it.name in onlyClips) }
                ?.sortedBy { it.name }.orEmpty()
            for (clip in clips) {
                val frames = clip.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty()
                for ((name, make) in looks) {
                    if (onlyLooks != null && name !in onlyLooks) continue
                    val out = File(filesDir, "out/${clip.name}/$name").apply { mkdirs() }
                    // One processor per sequence: tracking restarts cleanly for every look.
                    val processor = BeautyImageProcessor(this, BeautyConfig.forPhotos().apply { maxFaces = 1 })
                    val params = make()
                    frames.forEachIndexed { i, f ->
                        val src = BitmapFactory.decodeFile(f.path)
                        val result = processor.processVideoFrame(src, params, i * 66L + 1)
                        File(out, f.name.replace(".png", ".jpg")).outputStream().use {
                            result.compress(Bitmap.CompressFormat.JPEG, 92, it)
                        }
                        src.recycle()
                        result.recycle()
                    }
                    processor.close()
                    val msg = "${clip.name}/$name: ${frames.size} frames"
                    Log.i("FaceBeautyDemo", msg)
                    runOnUiThread { text.text = msg }
                }
            }
            Log.i("FaceBeautyDemo", "DONE")
            runOnUiThread { text.text = "DONE" }
        }
    }
}
