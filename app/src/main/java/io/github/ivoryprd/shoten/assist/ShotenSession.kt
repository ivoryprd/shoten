package io.github.ivoryprd.shoten.assist

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import io.github.ivoryprd.shoten.OcrMode
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.ShotenApp
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.ocr.OcrScript
import io.github.ivoryprd.shoten.overlay.ScreenLookupView
import io.github.ivoryprd.shoten.overlay.ScreenshotView
import io.github.ivoryprd.shoten.ui.MainActivity

/**
 * The assistant session: a full-screen, initially transparent window. When the system delivers
 * the screenshot of the app below, it is shown frozen and its text is recognized.
 */
class ShotenSession(context: Context) : VoiceInteractionSession(context), ScreenLookupView.Host {
    private val handler = Handler(Looper.getMainLooper())
    private var view: ScreenLookupView? = null
    private var viewUsed = false
    private var shown = false
    private var screenshotHandled = false

    // The screenshot can in theory arrive before onShow(); keep it until then.
    private var earlyScreenshot: Bitmap? = null
    private var earlyScreenshotNull = false
    private var earlyScreenshotTime = 0L
    private var hiddenTime = 0L

    private val screenshotTimeout = Runnable {
        if (shown && !screenshotHandled) showNoScreenshot()
    }

    init {
        setTheme(R.style.Theme_Shoten_Assist)
    }

    override fun onCreateContentView(): View {
        viewUsed = false
        return ScreenLookupView(context, this).also { view = it }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (shown) {
            // The assist gesture was used again while Shoten is open: close it. (The screenshot
            // taken for this request would show Shoten itself.)
            hide()
            return
        }
        configureWindow()
        if (view == null || viewUsed) {
            view?.release()
            val fresh = ScreenLookupView(context, this)
            setContentView(fresh)
            view = fresh
        }
        viewUsed = true
        shown = true
        screenshotHandled = false
        view?.onShown()

        // Load the OCR model while the screenshot is being taken.
        val app = ShotenApp.get(context)
        val chinese = app.prefs.ocrMode == OcrMode.CHINESE ||
            (app.prefs.ocrMode == OcrMode.AUTO && !app.dictionaries.hasDictionaries(Language.JAPANESE) &&
                app.dictionaries.hasDictionaries(Language.CHINESE))
        app.ocr.warmUp(if (chinese) OcrScript.CHINESE else OcrScript.JAPANESE)

        val early = SystemClock.elapsedRealtime() - earlyScreenshotTime < EARLY_SCREENSHOT_MAX_AGE
        when {
            early && earlyScreenshot != null -> deliver(earlyScreenshot)
            early && earlyScreenshotNull -> deliver(null)
            showFlags and SHOW_WITH_SCREENSHOT == 0 -> showNoScreenshot()
            else -> handler.postDelayed(screenshotTimeout, SCREENSHOT_TIMEOUT)
        }
        earlyScreenshot = null
        earlyScreenshotNull = false
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (!shown) {
            // A screenshot arriving right after the session was hidden belongs to that request.
            if (SystemClock.elapsedRealtime() - hiddenTime < EARLY_SCREENSHOT_MAX_AGE) return
            earlyScreenshot = screenshot
            earlyScreenshotNull = screenshot == null
            earlyScreenshotTime = SystemClock.elapsedRealtime()
            return
        }
        if (!screenshotHandled) deliver(screenshot)
    }

    private fun deliver(screenshot: Bitmap?) {
        screenshotHandled = true
        handler.removeCallbacks(screenshotTimeout)
        val v = view ?: return
        if (screenshot == null) {
            Log.w(TAG, "No screenshot (disabled in the assistant settings or blocked by the app)")
            showNoScreenshot()
        } else {
            Log.d(TAG, "Screenshot ${screenshot.width}x${screenshot.height} ${screenshot.config}")
            v.showImage(screenshot, ScreenshotView.Mode.SCREEN)
        }
    }

    private fun showNoScreenshot() {
        screenshotHandled = true
        view?.showError(
            context.getString(R.string.error_no_screenshot),
            context.getString(R.string.action_open_assist_settings),
        ) { openAssistSettings() }
    }

    override fun onHide() {
        super.onHide()
        shown = false
        hiddenTime = SystemClock.elapsedRealtime()
        handler.removeCallbacks(screenshotTimeout)
        view?.release()
        earlyScreenshot = null
    }

    override fun onDestroy() {
        view?.release()
        view = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (view?.onBackPressed() != true) hide()
    }

    // --- ScreenLookupView.Host --------------------------------------------------------------

    override fun dismiss() = hide()

    override fun openApp() {
        start(Intent(context, MainActivity::class.java))
    }

    override fun openAssistSettings() {
        val opened = start(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) ||
            start(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) ||
            start(Intent(Settings.ACTION_SETTINGS))
        if (!opened) hide()
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        hide()
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun configureWindow() {
        val w = window?.window ?: return
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.attributes = w.attributes.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            w.isNavigationBarContrastEnforced = false
            @Suppress("DEPRECATION") // Still needed before Android 15.
            w.isStatusBarContrastEnforced = false
        }
        // The frozen screenshot already shows the status bar; hide the live one on top of it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") // Edge-to-edge is the default from Android 15; needed before.
            w.setDecorFitsSystemWindows(false)
            w.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    companion object {
        private const val TAG = "ShotenSession"
        private const val SCREENSHOT_TIMEOUT = 2500L
        private const val EARLY_SCREENSHOT_MAX_AGE = 2000L
    }
}
