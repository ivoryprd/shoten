package io.github.ivoryprd.shoten.assist

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Lets Shoten be chosen as the default digital assistant. The system then opens a
 * [ShotenSession] (with a screenshot of the current app) whenever the assist gesture is used,
 * e.g. holding the home button or the navigation handle.
 */
class ShotenAssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        instance = null
        super.onShutdown()
    }

    /** Opens the screen lookup as if the assist gesture had been used. */
    fun showScreenLookup() {
        showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }

    companion object {
        /** The running service while Shoten is the active assistant. */
        @Volatile
        var instance: ShotenAssistService? = null
            private set

        fun isActive(context: Context): Boolean =
            isActiveService(context, ComponentName(context, ShotenAssistService::class.java))
    }
}

class ShotenSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = ShotenSession(this)
}
