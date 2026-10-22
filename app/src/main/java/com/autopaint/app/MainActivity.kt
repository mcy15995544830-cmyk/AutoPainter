package com.autopaint.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Service
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            ImageRepository.selectedImageUri = it
            Toast.makeText(this, "图片加载成功，请开启悬浮窗！", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
        }
        val btnSelect = Button(this).apply { 
            text = "1. 选择本地线稿图片"
            setOnClickListener { pickImageLauncher.launch("image/*") }
        }
        val btnAccessibility = Button(this).apply { 
            text = "2. 开启无障碍绘制服务"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val btnFloating = Button(this).apply { 
            text = "3. 开启悬浮控制与画框"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    startService(Intent(this@MainActivity, FloatingWindowService::class.java))
                }
            }
        }
        layout.addView(btnSelect)
        layout.addView(btnAccessibility)
        layout.addView(btnFloating)
        setContentView(layout)
    }
}

object ImageRepository { var selectedImageUri: Uri? = null }

class AutoPaintService : AccessibilityService() {
    companion object { var instance: AutoPaintService? = null }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { instance = null }

    fun dispatchClickPoint(x: Float, y: Float, onComplete: () -> Unit) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 15)
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onComplete()
            }
        }, null)
    }
}

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var controlView: View
    private lateinit var overlayBoxView: View
    private var isPainting = false
    private var isPaused = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        overlayBoxView = View(this).apply { setBackgroundColor(0x223B82F6) }
        val boxParams = WindowManager.LayoutParams(
            512, 512,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        windowManager.addView(overlayBoxView, boxParams)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xEE1E293B.toInt())
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val btnStart = Button(this).apply { text = "开始" }
        val btnPause = Button(this).apply { text = "暂停" }
        val btnClose = Button(this).apply { text = "关闭" }

        btnStart.setOnClickListener { if (!isPainting) startPainting(progressBar) }
        btnPause.setOnClickListener {
            isPaused = !isPaused
            btnPause.text = if (isPaused) "继续" else "暂停"
        }
        btnClose.setOnClickListener { stopSelf() }

        layout.addView(progressBar)
        layout.addView(btnStart)
        layout.addView(btnPause)
        layout.addView(btnClose)

        val controlParams = WindowManager.LayoutParams(
            300, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 100
        }
        controlView = layout
        windowManager.addView(controlView, controlParams)
    }

    private fun startPainting(progressBar: ProgressBar) {
        val uri = ImageRepository.selectedImageUri ?: return
        isPainting = true
        thread {
            val bitmap = android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
            val points = LineExtractor.extract(bitmap, overlayBoxView.width, overlayBoxView.height)
            val loc = IntArray(2)
            overlayBoxView.getLocationOnScreen(loc)
            val service = AutoPaintService.instance ?: return@thread

            var idx = 0
            while (idx < points.size && isPainting) {
                if (isPaused) { Thread.sleep(150); continue }
                val pt = points[idx]
                val lock = java.lang.Object()
                service.dispatchClickPoint(loc[0] + pt.first, loc[1] + pt.second) {
                    synchronized(lock) { lock.notify() }
                }
                synchronized(lock) { lock.wait(50) }
                idx++
                val prog = ((idx.toFloat() / points.size) * 100).toInt()
                controlView.post { progressBar.progress = prog }
            }
            isPainting = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::controlView.isInitialized) windowManager.removeView(controlView)
        if (::overlayBoxView.isInitialized) windowManager.removeView(overlayBoxView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

object LineExtractor {
    fun extract(bitmap: android.graphics.Bitmap, w: Int, h: Int): List<Pair<Float, Float>> {
        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
        val list = mutableListOf<Pair<Float, Float>>()
        for (y in 0 until h step 4) {
            for (x in 0 until w step 4) {
                val pixel = scaled.getPixel(x, y)
                val gray = (android.graphics.Color.red(pixel) * 0.3 + 
                             android.graphics.Color.green(pixel) * 0.59 + 
                             android.graphics.Color.blue(pixel) * 0.11).toInt()
                if (gray < 120) list.add(Pair(x.toFloat(), y.toFloat()))
            }
        }
        return list
    }
}
