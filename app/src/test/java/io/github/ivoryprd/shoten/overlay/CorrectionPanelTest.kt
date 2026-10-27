package io.github.ivoryprd.shoten.overlay

import android.app.Application
import android.view.View
import io.github.ivoryprd.shoten.R
import io.github.ivoryprd.shoten.render.ResultsView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CorrectionPanelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val events = ArrayList<String>()
    private val panel = CorrectionPanel(context, ResultsView.Ui(context), object : CorrectionPanel.Listener {
        override fun onCharSelected(index: Int) {
            events += "select $index"
        }

        override fun onCorrect(index: Int, replacement: String?) {
            events += "correct $index ${replacement ?: "(delete)"}"
        }

        override fun onDone() {
            events += "done"
        }
    })

    /** The views with [label] as text or description. */
    private fun find(label: String): List<View> = ArrayList<View>().also {
        panel.findViewsWithText(it, label, View.FIND_VIEWS_WITH_TEXT or View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
    }.distinct()

    @Test
    fun selectsAndCorrectsCharacters() {
        panel.setText(listOf("末", "来", "は"), 0)
        assertEquals(1, find(context.getString(R.string.correction_loading)).size)
        find("来").single().performClick()
        find("末").single().performClick() // Already selected.
        assertEquals(listOf("select 1"), events)
        events.clear()

        panel.setSuggestions(listOf(CorrectionPanel.Suggestion("未", true), CorrectionPanel.Suggestion("米", false)))
        // A suggestion that makes a word says so.
        assertEquals(1, find(context.getString(R.string.correction_makes_word, "未")).size)
        find(context.getString(R.string.correction_makes_word, "未")).single().performClick()
        find("米").single().performClick()
        find(context.getString(R.string.action_delete_character)).single().performClick()
        find(context.getString(R.string.action_done)).single().performClick()
        assertEquals(listOf("correct 0 未", "correct 0 米", "correct 0 (delete)", "done"), events)
    }

    @Test
    fun noSuggestions() {
        panel.setText(listOf("x"), 0)
        panel.setSuggestions(emptyList())
        assertEquals(1, find(context.getString(R.string.correction_none)).size)
        // Deleting is still possible.
        assertTrue(find(context.getString(R.string.action_delete_character)).isNotEmpty())
    }
}
