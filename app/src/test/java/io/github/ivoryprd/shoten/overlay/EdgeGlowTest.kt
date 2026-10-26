package io.github.ivoryprd.shoten.overlay

import android.app.Application
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compiles the glow's shader with Skia (real native graphics), as Android 13+ does at runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgeGlowTest {
    @Test
    fun shaderCompiles() {
        assertNotNull(EdgeGlow.create(0xFF1A73E8.toInt(), 0xFF8AB4F8.toInt()))
    }
}
