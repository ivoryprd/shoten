package io.github.ivoryprd.shoten.dict

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.ShotenApp
import io.github.ivoryprd.shoten.dict.importer.ImportCancelledException
import io.github.ivoryprd.shoten.dict.importer.ImportProgress
import io.github.ivoryprd.shoten.ui.DictionariesActivity
import io.github.ivoryprd.shoten.util.string
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Progress of dictionary downloads and imports, observed by the UI on the main thread. */
class ImportState {
    data class Status(
        val running: Boolean = false,
        val label: String = "",
        val fraction: Float = -1f,
        val queued: Int = 0,
        /** Message about the last finished job (success or error), shown until the next job. */
        val lastMessage: String? = null,
        val lastFailed: Boolean = false,
    )

    @Volatile
    var status = Status()
        private set
    private val listeners = ArrayList<(Status) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    fun update(change: (Status) -> Status) {
        synchronized(this) { status = change(status) }
        val snapshot = status
        handler.post { listeners.toList().forEach { it(snapshot) } }
    }

    fun addListener(listener: (Status) -> Unit) {
        listeners.add(listener)
        listener(status)
    }

    fun removeListener(listener: (Status) -> Unit) {
        listeners.remove(listener)
    }
}

/**
 * Downloads and imports dictionaries in the background as a foreground service, so that large
 * imports (Jitendex takes a few minutes on a phone) finish even if the user leaves the app.
 */
class ImportService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val pendingJobs = AtomicInteger()
    private val activeStarts = AtomicInteger()

    @Volatile
    private var cancelled = false
    private var lastStartId = 0
    private var lastNotificationUpdate = 0L
    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.import_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            // Sent with startService(), so no startForeground() is required here.
            cancelled = true
            stopIfIdle()
            return START_NOT_STICKY
        }
        startInForeground(getString(R.string.import_preparing), -1f)
        val job: (() -> Unit)? = when (action) {
            ACTION_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: getString(R.string.import_dictionary)
                val replaceId = intent.getStringExtra(EXTRA_REPLACE_ID)
                if (url == null) null else ({ runDownload(url, label, replaceId) })
            }
            ACTION_IMPORT -> {
                val uri = intent.data
                if (uri == null) null else ({ runImportUri(uri) })
            }
            ACTION_UPDATE -> {
                val id = intent.getStringExtra(EXTRA_REPLACE_ID)
                if (id == null) null else ({ runUpdate(id) })
            }
            else -> null
        }
        if (job == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        app.imports.update { it.copy(queued = pendingJobs.incrementAndGet()) }
        activeStarts.incrementAndGet()
        executor.execute {
            cancelled = false
            try {
                job()
            } finally {
                app.imports.update { it.copy(running = false, queued = pendingJobs.decrementAndGet()) }
                activeStarts.decrementAndGet()
                Handler(Looper.getMainLooper()).post { stopIfIdle() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits data sync services to 6 hours; give up rather than be killed.
        cancelled = true
        stopSelf()
    }

    override fun onDestroy() {
        cancelled = true
        executor.shutdownNow()
        super.onDestroy()
    }

    private val app get() = ShotenApp.get(this)

    /**
     * Runs on the main thread, like onStartCommand. stopSelfResult() doesn't stop the service if
     * a newer start request is already pending.
     */
    private fun stopIfIdle() {
        if (activeStarts.get() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(lastStartId)
        }
    }

    private val progress = object : ImportProgress {
        override fun onProgress(fraction: Float, message: String) {
            app.imports.update { it.copy(running = true, label = message, fraction = fraction) }
            val now = System.currentTimeMillis()
            if (now - lastNotificationUpdate > 500) {
                lastNotificationUpdate = now
                notificationManager.notify(NOTIFICATION_ID, buildNotification(message, fraction))
            }
        }

        override val isCancelled: Boolean get() = cancelled
    }

    private fun runDownload(url: String, label: String, replaceId: String?) {
        val file = File(cacheDir, "download-" + System.nanoTime())
        runJob(label) {
            progress.onProgress(-1f, getString(R.string.import_downloading, label))
            DictionaryInstaller.download(url, file, progress, getString(R.string.import_downloading, label))
            progress.onProgress(0f, getString(R.string.import_importing, label))
            installFile(file, label, replaceId)
        }
        file.delete()
    }

    private fun runImportUri(uri: Uri) {
        val file = File(cacheDir, "import-" + System.nanoTime())
        val label = getString(R.string.import_dictionary)
        runJob(label) {
            progress.onProgress(-1f, getString(R.string.import_copying))
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open the file" }
                file.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            installFile(file, label, null)
        }
        file.delete()
    }

    private fun runUpdate(id: String) {
        val entry = app.dictionaries.installed.firstOrNull { it.id == id } ?: return
        val file = File(cacheDir, "update-" + System.nanoTime())
        runJob(entry.title) {
            progress.onProgress(-1f, getString(R.string.import_checking_updates, entry.title))
            val indexUrl = entry.indexUrl ?: throw IllegalStateException(getString(R.string.import_not_updatable))
            val index = io.github.ivoryprd.shoten.dict.importer.YomitanImporter.parseIndex(DictionaryInstaller.fetchText(indexUrl))
            val revision = index.string("revision")
            if (revision == null || revision == entry.revision) {
                app.imports.update { it.copy(lastMessage = getString(R.string.import_up_to_date, entry.title), lastFailed = false) }
                return@runJob
            }
            val downloadUrl = index.string("downloadUrl") ?: entry.downloadUrl
                ?: throw IllegalStateException(getString(R.string.import_not_updatable))
            val label = entry.title
            DictionaryInstaller.download(downloadUrl, file, progress, getString(R.string.import_downloading, label))
            installFile(file, label, id)
        }
        file.delete()
    }

    private fun installFile(file: File, label: String, replaceId: String?) {
        progress.onProgress(0f, getString(R.string.import_importing, label))
        val installed = DictionaryInstaller(app.dictionaries).install(file, progress, replaceId)
        app.imports.update { it.copy(lastMessage = getString(R.string.import_done, installed.title), lastFailed = false) }
        notificationManager.notify(DONE_NOTIFICATION_ID, buildDoneNotification(getString(R.string.import_done, installed.title)))
    }

    private inline fun runJob(label: String, block: () -> Unit) {
        app.imports.update { it.copy(running = true, label = label, fraction = -1f, lastMessage = null, lastFailed = false) }
        try {
            block()
        } catch (e: ImportCancelledException) {
            app.imports.update { it.copy(lastMessage = getString(R.string.import_cancelled), lastFailed = true) }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            val message = getString(R.string.import_failed, e.message ?: e.javaClass.simpleName)
            app.imports.update { it.copy(lastMessage = message, lastFailed = true) }
            notificationManager.notify(DONE_NOTIFICATION_ID, buildDoneNotification(message))
        } catch (e: OutOfMemoryError) {
            val message = getString(R.string.import_failed, "out of memory")
            app.imports.update { it.copy(lastMessage = message, lastFailed = true) }
        }
    }

    private fun startInForeground(text: String, fraction: Float) {
        val notification = buildNotification(text, fraction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, DictionariesActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(text: String, fraction: Float): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ImportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.import_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setProgress(1000, (fraction.coerceIn(0f, 1f) * 1000).toInt(), fraction < 0)
            .addAction(Notification.Action.Builder(null, getString(android.R.string.cancel), cancel).build())
            .build()
    }

    private fun buildDoneNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.import_notification_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()

    companion object {
        private const val TAG = "ImportService"
        private const val CHANNEL_ID = "imports"
        private const val NOTIFICATION_ID = 1
        private const val DONE_NOTIFICATION_ID = 2
        private const val ACTION_DOWNLOAD = "io.github.ivoryprd.shoten.action.DOWNLOAD"
        private const val ACTION_IMPORT = "io.github.ivoryprd.shoten.action.IMPORT"
        private const val ACTION_UPDATE = "io.github.ivoryprd.shoten.action.UPDATE"
        private const val ACTION_CANCEL = "io.github.ivoryprd.shoten.action.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_REPLACE_ID = "replace_id"

        fun download(context: Context, url: String, label: String, replaceId: String? = null) {
            start(context, Intent(context, ImportService::class.java).setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_LABEL, label).putExtra(EXTRA_REPLACE_ID, replaceId))
        }

        fun import(context: Context, uri: Uri) {
            start(context, Intent(context, ImportService::class.java).setAction(ACTION_IMPORT)
                .setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }

        fun update(context: Context, dictionaryId: String) {
            start(context, Intent(context, ImportService::class.java).setAction(ACTION_UPDATE).putExtra(EXTRA_REPLACE_ID, dictionaryId))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ImportService::class.java).setAction(ACTION_CANCEL))
        }

        private fun start(context: Context, intent: Intent) {
            context.startForegroundService(intent)
        }
    }
}
