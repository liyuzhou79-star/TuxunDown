package com.tuxundown.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.Camera
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.tuxundown.android.core.CreateDedup
import com.tuxundown.android.core.DownloadCenter
import com.tuxundown.android.core.LinkSchemes
import com.tuxundown.android.core.ThunderLink

/**
 * QR scan-to-add (v1.6): point the camera at a QR code, or pick a
 * screenshot/photo from the gallery, and a contained http(s) link
 * becomes a download task.
 *
 * Implementation notes (kept deliberately small): decoding is
 * zxing CORE only — no embedded scanner UI, no CameraX, no ML
 * Kit. Live preview uses the legacy Camera API, which still works
 * on every Android we target; if the camera is unavailable the
 * gallery path remains. Every failure degrades to a plain toast.
 */
@Suppress("DEPRECATION")
class ScanActivity : Activity() {

    private var camera: Camera? = null
    private var surfaceView: SurfaceView? = null
    @Volatile private var finished = false
    @Volatile private var lastAttemptMs = 0L
    // v1.7 audit (B3): decoding runs on ONE daemon thread, and the
    // preview buffer is COPIED before hand-off — the camera reuses
    // its buffer for the next frame, so decoding the original array
    // races the next frame and can read a torn image.
    private val decodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r).apply { isDaemon = true }
    }
    @Volatile private var decodeBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadCenter.init(this)

        val root = FrameLayout(this)
        val sv = SurfaceView(this)
        sv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        surfaceView = sv
        root.addView(sv)

        val hint = TextView(this).apply {
            text = "把二维码放进画面里，对准了会自动识别；也可以从相册选一张带二维码的图片。"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(28, 20, 28, 20)
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP },
        )

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val galleryBtn = Button(this).apply {
            text = "从相册选图"
            setOnClickListener { pickImage() }
        }
        val cancelBtn = Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        }
        bar.addView(galleryBtn)
        bar.addView(cancelBtn)
        val barParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        root.addView(bar, barParams)
        // v2.1 safe-area pass: the camera preview stays full-bleed
        // (it is a viewfinder), but on Android 15's edge-to-edge
        // windows the top hint would hide under the status bar and
        // the bottom buttons under the gesture bar — lift just the
        // two control layers by the system-bar insets.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
            )
            hint.setPadding(28, 20 + bars.top, 28, 20)
            barParams.bottomMargin = bars.bottom + 24
            bar.layoutParams = barParams
            insets
        }
        setContentView(root)

        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (hasCameraPermission()) startCamera(holder)
            }
            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int,
            ) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = stopCamera()
        })

        if (!hasCameraPermission()) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                surfaceView?.holder?.let { startCamera(it) }
            } else {
                Toast.makeText(
                    this, "没有相机权限，可以用「从相册选图」识别二维码",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onPause() {
        stopCamera()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // v1.9 audit: onPause releases the camera, but the only
        // restart paths were surfaceCreated and the permission
        // callback. When the surface SURVIVES a pause (translucent
        // overlay, multi-window), neither fires again and the
        // preview stayed black until the screen was recreated.
        // startCamera already no-ops when a camera is held.
        if (hasCameraPermission()) {
            surfaceView?.holder?.let { startCamera(it) }
        }
    }

    override fun onDestroy() {
        // v1.9 audit: belt and braces — never outlive the screen
        // holding the camera, even on destroy-without-pause paths.
        stopCamera()
        decodeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera(holder: SurfaceHolder) {
        if (camera != null || finished) return
        val cam = runCatching { Camera.open() }.getOrNull()
        if (cam == null) {
            Toast.makeText(
                this, "相机打不开，可以用「从相册选图」识别二维码", Toast.LENGTH_LONG
            ).show()
            return
        }
        camera = cam
        runCatching {
            cam.setPreviewDisplay(holder)
            runCatching {
                val params = cam.parameters
                if (params.supportedFocusModes?.contains(
                        Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                    ) == true
                ) {
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                }
                cam.parameters = params
            }
            cam.setPreviewCallback { data, c ->
                if (data != null) onPreviewFrame(data, c)
            }
            cam.startPreview()
        }.onFailure {
            Toast.makeText(
                this, "相机预览失败，可以用「从相册选图」识别二维码", Toast.LENGTH_LONG
            ).show()
            stopCamera()
        }
    }

    private fun stopCamera() {
        runCatching {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        }
        camera = null
    }

    private fun onPreviewFrame(data: ByteArray, cam: Camera) {
        if (finished || decodeBusy) return
        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < DECODE_INTERVAL_MS) return
        lastAttemptMs = now
        val size = runCatching { cam.parameters.previewSize }.getOrNull() ?: return
        val frame = data.copyOf()
        val w = size.width
        val h = size.height
        decodeBusy = true
        decodeExecutor.execute {
            try {
                val text = runCatching {
                    val src = PlanarYUVLuminanceSource(
                        frame, w, h, 0, 0, w, h, false
                    )
                    // Portrait phones deliver a sideways sensor image;
                    // try both orientations.
                    decode(src) ?: decode(src.rotateCounterClockwise())
                }.getOrNull()
                if (text != null) runOnUiThread { onDecoded(text) }
            } finally {
                decodeBusy = false
            }
        }
    }

    private fun decode(source: com.google.zxing.LuminanceSource): String? =
        runCatching {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        }.getOrNull()

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_GALLERY)
    }

    @Deprecated("legacy result flow, matches the rest of this screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_GALLERY || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val text = runCatching {
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: error("no image")
                val scale = maxOf(1, maxOf(bmp.width, bmp.height) / 2048)
                val scaled = if (scale > 1) {
                    android.graphics.Bitmap.createScaledBitmap(
                        bmp, bmp.width / scale, bmp.height / scale, true
                    )
                } else bmp
                val w = scaled.width
                val h = scaled.height
                val pixels = IntArray(w * h)
                scaled.getPixels(pixels, 0, w, 0, 0, w, h)
                decode(RGBLuminanceSource(w, h, pixels))
            }.getOrNull()
            runOnUiThread {
                if (text != null) {
                    onDecoded(text)
                } else {
                    Toast.makeText(this, "这张图里没认出二维码", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun onDecoded(raw: String) {
        if (finished) return
        // v2.5: a QR code holding a thunder:// envelope decodes
        // to the real address first (the https? pattern below
        // cannot see inside the envelope).
        val url = ThunderLink.findDecoded(raw).firstOrNull()
            ?: Regex("https?://\\S+").find(raw.trim())?.value
                ?.trimEnd('.', ',', '，', '。', '"', '\'')
        if (url.isNullOrBlank()) {
            Toast.makeText(
                this, "二维码里不是链接：${raw.take(40)}", Toast.LENGTH_LONG
            ).show()
            return
        }
        // v4.7 audit round 32: every other intake funnel passes
        // the LinkSchemes gate before a task is created — the
        // scan funnel does too, in depth (its own extraction in
        // fact only yields http/https or a ThunderLink decode,
        // whose inners are already restricted to http/https/ftp).
        if (!LinkSchemes.isSupported(url)) {
            Toast.makeText(
                this, "二维码里不是链接：${raw.take(40)}", Toast.LENGTH_LONG
            ).show()
            return
        }
        finished = true
        stopCamera()
        // v5.4: the ONE dedup verdict (see CreateDedup), same
        // as every other creation funnel.
        val dup = CreateDedup.isDuplicate(DownloadCenter.store.tasks, url)
        if (dup) {
            Toast.makeText(this, "这个链接已经在任务列表里了", Toast.LENGTH_LONG).show()
        } else {
            val task = DownloadCenter.newTask(url)
            DownloadCenter.store.add(task)
            DownloadCenter.enqueue(task)
            Toast.makeText(this, "扫码成功，已新建下载任务", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        private const val REQ_CAMERA = 7
        private const val REQ_GALLERY = 42
        private const val DECODE_INTERVAL_MS = 300L
    }
}
