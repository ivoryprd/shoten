package io.github.ivoryprd.shoten.ui

import android.content.Intent
import android.graphics.Bitmap
import io.github.ivoryprd.shoten.overlay.ScreenLookupView
import io.github.ivoryprd.shoten.overlay.ScreenshotView
import com.google.mlkit.common.sdkinternal.MlKitContext
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/** Launches every screen with the real application class to catch startup crashes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LaunchTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun initMlKit() {
        // On devices ML Kit's MlKitInitProvider does this before the app starts; Robolectric
        // doesn't run content providers.
        MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication())
    }

    @Test
    fun mainActivity() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        controller.pause().stop().start().resume()
        idle()
        controller.pause().stop().destroy()
    }

    @Test
    fun dictionariesActivity() {
        Robolectric.buildActivity(DictionariesActivity::class.java).setup().also { idle() }.pause().stop().destroy()
    }

    @Test
    fun settingsActivity() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().also { idle() }.pause().stop().destroy()
    }

    @Test
    fun textLookupActivity() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "食べた")
        Robolectric.buildActivity(TextLookupActivity::class.java, intent).setup().also { idle() }.pause().stop().destroy()
    }

    @Test
    fun imageLookupActivityWithoutImage() {
        Robolectric.buildActivity(ImageLookupActivity::class.java, Intent(Intent.ACTION_SEND)).setup().also { idle() }
    }

    @Test
    fun screenLookupView() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val view = ScreenLookupView(activity, object : ScreenLookupView.Host {
            override fun dismiss() = Unit
            override fun openApp() = Unit
            override fun openAssistSettings() = Unit
        })
        activity.setContentView(view)
        idle()
        view.showError("error", "action") {}
        view.showImage(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888), ScreenshotView.Mode.FIT)
        idle()
        view.onBackPressed()
        view.release()
    }
}
