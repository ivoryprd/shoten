package io.github.ivoryprd.shoten.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.ShotenApp
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.lookup.LookupResult
import io.github.ivoryprd.shoten.render.LinkHandler
import io.github.ivoryprd.shoten.render.RenderedResult
import io.github.ivoryprd.shoten.render.ResultPresenter
import io.github.ivoryprd.shoten.render.ResultsView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs lookups in the background and shows them in a [ResultsView]. Links inside definitions
 * start nested lookups, which [back] returns from.
 */
class LookupController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val view: ResultsView,
    private val listener: Listener,
) : LinkHandler {
    interface Listener {
        /** Called on the main thread after results are shown. [nested] is true for link lookups. */
        fun onResult(result: LookupResult, nested: Boolean)

        /** Called before opening an external link (e.g. to close an overlay). */
        fun onLeaving() {}
    }

    private val app = ShotenApp.get(context)
    private var presenter: ResultPresenter? = null
    private var presenterScale = 0f
    private var job: Job? = null
    private val history = ArrayList<Pair<String, Language>>()
    private var current: Pair<String, Language>? = null

    val canGoBack: Boolean get() = history.isNotEmpty()

    /** The presenter for the current text size setting (which can change while a screen is open). */
    private fun presenter(): ResultPresenter {
        val scale = app.prefs.textScale
        val current = presenter
        if (current != null && scale == presenterScale) return current
        view.textScale = scale
        presenterScale = scale
        return ResultPresenter(ResultsView.theme(context, scale), app.prefs, this).also { presenter = it }
    }

    /** Looks up [text]. A new top-level lookup clears the link history. */
    fun lookup(text: String, language: Language) {
        history.clear()
        run(text, language, nested = false)
    }

    fun back(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        run(previous.first, previous.second, nested = history.isNotEmpty())
        return true
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun run(text: String, language: Language, nested: Boolean) {
        current = text to language
        job?.cancel()
        val presenter = presenter()
        job = scope.launch {
            val rendered: RenderedResult = try {
                withContext(app.lookupDispatcher) {
                    val result = app.lookupEngine.lookup(text, language)
                    presenter.present(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                view.showMessage(context.getString(R.string.lookup_failed, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            if (rendered.result.isEmpty) {
                view.showMessage(context.getString(R.string.no_results, rendered.result.text.ifEmpty { text }))
            } else {
                view.bind(rendered)
            }
            listener.onResult(rendered.result, nested)
        }
    }

    override fun onLookup(query: String) {
        val cur = current ?: return
        history.add(cur)
        run(query, cur.second, nested = true)
    }

    override fun onOpenUrl(url: String) {
        listener.onLeaving()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, url, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onShowNote(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
