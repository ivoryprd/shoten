package io.github.ivoryprd.shoten.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.overlay.ScreenLookupView
import io.github.ivoryprd.shoten.overlay.ScreenshotView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Recognizes and looks up text in an image shared to Shoten (for example a screenshot). */
class ImageLookupActivity : Activity(), ScreenLookupView.Host {
    private val scope = MainScope()
    private lateinit var view: ScreenLookupView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        view = ScreenLookupView(this, this)
        setContentView(view)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { if (!view.onBackPressed()) finish() },
            )
        }
        val uri = imageUri(intent)
        if (uri == null) {
            finish()
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { decode(uri) }.getOrNull() }
            if (bitmap == null) {
                Toast.makeText(this@ImageLookupActivity, R.string.image_load_failed, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                view.showImage(bitmap, ScreenshotView.Mode.FIT)
            }
        }
    }

    override fun onDestroy() {
        if (::view.isInitialized) view.release()
        scope.cancel()
        super.onDestroy()
    }

    // Only reached on Android 12 and older; newer versions use the OnBackInvokedCallback above.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!view.onBackPressed()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun dismiss() = finish()

    override fun openApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun openAssistSettings() = openAssistantSettings()

    private fun imageUri(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_SEND) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        }
        return intent.data
    }

    /** Decodes the image, scaled down so that its longer side is at most [MAX_SIZE] pixels. */
    private fun decode(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_SIZE) {
                    val scale = MAX_SIZE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIZE) sample *= 2
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    companion object {
        private const val MAX_SIZE = 3000
    }
}
