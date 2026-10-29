package com.quran.mobile.recitation.presenter

import com.quran.data.di.QuranPageScope
import com.quran.data.model.SuraAyah
import com.quran.data.model.highlight.HighlightInfo
import com.quran.data.model.highlight.HighlightType
import com.quran.data.model.highlight.HighlightType.Mode
import com.quran.mobile.recitation.R
import com.quran.recitation.presenter.RecitationHighlightsPresenter
import com.quran.recitation.presenter.RecitationHighlightsPresenter.HighlightAction
import com.quran.recitation.presenter.RecitationHighlightsPresenter.RecitationPage
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Shows the recitation session on a mushaf page: recited words turn green, a word slipped within
 * an otherwise-recited ayah orange, and every word of an ayah the reader got wrong (see
 * [incorrectAyahs]) red — turning green again word by word as the reader re-recites it. The word
 * the reader is on is highlighted, and words not yet recited can
 * be hidden (keeping the ayah numbers). Pages taller than the screen scroll to keep the word the
 * reader is on in view. (Vowel-mistake highlighting, cyan, is kept but disabled; see
 * SHOW_WRONG_VOWELS.)
 *
 * Word highlights need the page's word coordinates (see AyahInfoDatabaseHandler).
 */
@QuranPageScope
class RecitationHighlightsPresenterImpl @Inject constructor(
  private val state: MushafRecitationState,
) : RecitationHighlightsPresenter {
  private var page: RecitationPage? = null
  private var scope: CoroutineScope? = null
  private var shown: List<HighlightInfo> = emptyList()

  override fun bind(what: RecitationPage) {
    page = what
    shown = emptyList()
    scope = MainScope().also { scope ->
      state.progress.onEach { show(it, force = false) }.launchIn(scope)
    }
  }

  override fun unbind(what: RecitationPage) {
    scope?.cancel()
    scope = null
    page = null
  }

  /** Called once the page's coordinates are loaded, so highlights can be drawn. */
  override fun refresh() {
    show(state.progress.value, force = true)
  }

  private fun show(progress: MushafRecitationProgress?, force: Boolean) {
    val page = page ?: return
    val highlights = progress?.let { highlightsFor(it, page.pageNumbers) }.orEmpty()
    // Most updates change nothing on a given page (the reader is on another one).
    if (!force && highlights.sameAs(shown)) return
    shown = highlights
    page.applyHighlights(
      buildList<HighlightAction> {
        TYPES.forEach { add(HighlightAction.UnhighlightAll(it)) }
        highlights.forEach { add(HighlightAction.Highlight(it)) }
      }
    )
  }

  private fun highlightsFor(progress: MushafRecitationProgress, pages: Set<Int>): List<HighlightInfo> {
    val incorrectAyahs = incorrectAyahs(progress)
    val result = ArrayList<HighlightInfo>()
    progress.words.forEachIndexed { index, word ->
      if (progress.pages[index] !in pages) return@forEachIndexed
      val ayah = word.word.ayah
      val position = word.word.wordPosition
      when {
        // Vowel-mistake (cyan) highlighting is disabled; see SHOW_WRONG_VOWELS.
        SHOW_WRONG_VOWELS && index in progress.wrongVowels -> result += highlight(ayah, position, WRONG_VOWEL)
        // A whole ayah the reader got wrong is red; but the reader is sent back to re-read it, so a
        // word turns green again once re-recited (passed by the expected word), the word expected
        // next scrolls into view, and the rest stay red until re-recited.
        ayah in incorrectAyahs -> result += when {
          index < progress.currentWord && index in progress.recited -> highlight(ayah, position, RECITED)
          index == progress.currentWord -> highlight(ayah, position, MISSED, scroll = true)
          else -> highlight(ayah, position, MISSED)
        }
        index in progress.recited -> result += highlight(ayah, position, RECITED)
        // A word slipped within an otherwise-recited ayah is orange, not red.
        index in progress.missed -> result += highlight(ayah, position, WRONG_WORD)
        // Word by word, so the ayah numbers stay visible as landmarks.
        progress.hideUnrecited && index >= progress.start ->
          result += highlight(ayah, position, HIDDEN, scroll = index == progress.currentWord)
        index == progress.currentWord -> result += highlight(ayah, position, CURRENT_WORD, scroll = true)
      }
    }
    return result
  }

  /**
   * The ayah the reader is being held in to re-read; its words are shown red until re-recited. The
   * follower sends the reader back into an ayah it judged wrong (too many words missed), so the
   * word expected next ends up inside an ayah whose words are all already marked (recited or
   * missed) — which never happens while first reciting an ayah (the word expected next is still
   * unmarked there). An ayah the reader has moved on from is not one of these, so its slips stay
   * orange. Keying the red off the hold (rather than re-counting the missed fraction each redraw)
   * keeps a re-recited ayah's not-yet-reached words red, instead of flipping them to orange once
   * enough were corrected.
   */
  private fun incorrectAyahs(progress: MushafRecitationProgress): Set<SuraAyah> {
    val current = progress.words.getOrNull(progress.currentWord)?.word?.ayah ?: return emptySet()
    val heldInCurrent = progress.words.withIndex()
      .filter { it.value.word.ayah == current }
      .all { it.index in progress.recited || it.index in progress.missed }
    return if (heldInCurrent) setOf(current) else emptySet()
  }

  /** [scroll]: keep the word on the screen (pages taller than it scroll to follow the reader). */
  private fun highlight(ayah: SuraAyah, word: Int, type: HighlightType, scroll: Boolean = false) =
    HighlightInfo(ayah.sura, ayah.ayah, word, type, scrollToAyah = scroll)

  private fun List<HighlightInfo>.sameAs(other: List<HighlightInfo>): Boolean =
    size == other.size && indices.all { i ->
      val a = this[i]
      val b = other[i]
      a.sura == b.sura && a.ayah == b.ayah && a.word == b.word && a.highlightType == b.highlightType
    }

  companion object {
    /** Whether a letter recited with the wrong vowel is shown (cyan). Kept but disabled for now. */
    private const val SHOW_WRONG_VOWELS = false

    // Ids after the app's own highlight types (selection, audio, bookmarks, colors, reading bookmarks).
    val RECITED = HighlightType(
      41, R.color.recitation_recited, Mode.COLOR, nightColorResId = R.color.recitation_recited_night,
    )
    val MISSED = HighlightType(
      42, R.color.recitation_missed, Mode.COLOR, nightColorResId = R.color.recitation_missed_night,
    )
    val HIDDEN = HighlightType(43, R.color.recitation_hidden, Mode.HIDE)
    val CURRENT_WORD = HighlightType(44, R.color.recitation_current_word, Mode.HIGHLIGHT)
    val WRONG_VOWEL = HighlightType(
      45, R.color.recitation_wrong_vowel, Mode.COLOR, nightColorResId = R.color.recitation_wrong_vowel_night,
    )
    val WRONG_WORD = HighlightType(
      46, R.color.recitation_wrong_word, Mode.COLOR, nightColorResId = R.color.recitation_wrong_word_night,
    )

    private val TYPES = listOf(RECITED, WRONG_VOWEL, WRONG_WORD, MISSED, HIDDEN, CURRENT_WORD)
  }
}
