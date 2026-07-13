package com.nieao.blindaid

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nieao.blindaid.databinding.ActivityMainBinding
import java.util.concurrent.Executors

/**
 * MVP 完整链路入口:CameraX 取帧 → PerceptionEngine(障碍检测 + 手指指向 + 距离分级融合)
 * → OverlayView 分级着色画框 + SpeechManager 中文播报。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var engine: PerceptionEngine? = null
    private var speech: SpeechManager? = null

    private var lastFpsTs = 0L
    private var frameCount = 0
    private var fps = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speech = SpeechManager(this)

        binding.switchModelBtn.setOnClickListener { engine?.switchModel() }

        if (hasCameraPermission()) {
            initEngine(); startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            initEngine(); startCamera()
        } else {
            Toast.makeText(this, R.string.need_camera, Toast.LENGTH_LONG).show()
        }
    }

    private fun initEngine() {
        engine = try {
            PerceptionEngine(this)
        } catch (e: Throwable) {
            Log.e(TAG, "engine init fail", e)
            Toast.makeText(this, "感知引擎初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> onFrame(proxy) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "camera bind fail", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(proxy: ImageProxy) {
        val eng = engine
        if (eng == null) { proxy.close(); return }
        try {
            val bitmap = proxy.toRotatedBitmap()
            val result = eng.process(FrameInput(bitmap, System.currentTimeMillis()))

            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTs >= 1000) {
                fps = frameCount * 1000f / (now - lastFpsTs).coerceAtLeast(1)
                frameCount = 0; lastFpsTs = now
            }

            runOnUiThread {
                binding.overlay.setResults(result.obstacles, result.pointer, result.pointedTarget)
                binding.statusText.text =
                    "${eng.statusLine}\nFPS: ${"%.1f".format(fps)}   目标: ${result.obstacles.size}"
            }
            speech?.speak(result.announcement)
        } catch (e: Throwable) {
            Log.e(TAG, "frame error", e)
        } finally {
            proxy.close()
        }
    }

    private fun ImageProxy.toRotatedBitmap(): Bitmap {
        val bmp = toBitmap()
        val rot = imageInfo.rotationDegrees
        if (rot == 0) return bmp
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.close()
        speech?.shutdown()
        analysisExecutor.shutdown()
    }

    companion object { private const val TAG = "BlindAid" }
}
