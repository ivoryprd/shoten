package io.github.ivoryprd.shoten.ocr

import android.content.Context
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.text.TextRecognizer

/**
 * The "standard" build gets its OCR models from Google Play services, which downloads them on
 * demand. This keeps the APK small.
 */
object OcrModels {
    const val DOWNLOADED_BY_PLAY_SERVICES = true

    fun checkAvailable(context: Context, recognizer: TextRecognizer, callback: (Boolean) -> Unit) {
        ModuleInstall.getClient(context).areModulesAvailable(recognizer)
            .addOnSuccessListener { callback(it.areModulesAvailable()) }
            .addOnFailureListener { callback(false) }
    }

    /**
     * Requests the model download. [onProgress] receives 0..100 (or -1 when unknown); [onDone]
     * receives whether the model is ready, plus an error message.
     */
    fun install(context: Context, recognizer: TextRecognizer, onProgress: (Int) -> Unit, onDone: (Boolean, String?) -> Unit) {
        val client = ModuleInstall.getClient(context)
        var finished = false
        lateinit var listener: InstallStatusListener
        listener = InstallStatusListener { update ->
            val info = update.progressInfo
            if (info != null && info.totalBytesToDownload > 0) {
                onProgress((info.bytesDownloaded * 100 / info.totalBytesToDownload).toInt())
            } else {
                onProgress(-1)
            }
            when (update.installState) {
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                    if (!finished) { finished = true; onDone(true, null) }
                    client.unregisterListener(listener)
                }
                ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                    if (!finished) { finished = true; onDone(false, "Download failed (${update.errorCode})") }
                    client.unregisterListener(listener)
                }
            }
        }
        val request = ModuleInstallRequest.newBuilder().addApi(recognizer).setListener(listener).build()
        client.installModules(request)
            .addOnSuccessListener { response ->
                if (response.areModulesAlreadyInstalled() && !finished) {
                    finished = true
                    client.unregisterListener(listener)
                    onDone(true, null)
                }
            }
            .addOnFailureListener { e ->
                if (!finished) { finished = true; onDone(false, e.message) }
                client.unregisterListener(listener)
            }
    }
}
