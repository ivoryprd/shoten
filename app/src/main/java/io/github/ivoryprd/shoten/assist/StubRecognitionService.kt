package io.github.ivoryprd.shoten.assist

import android.content.Intent
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android only accepts assistants that declare a speech recognition service. Shoten does not do
 * speech recognition, so this one immediately reports an error. It is marked as not selectable
 * as the default recognizer, so it never replaces the system's real one.
 */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: RemoteException) {
            // The caller is gone.
        }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
