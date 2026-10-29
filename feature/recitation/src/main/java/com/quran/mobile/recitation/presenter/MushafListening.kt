package com.quran.mobile.recitation.presenter

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import com.quran.data.di.AppScope
import com.quran.data.model.SuraAyah
import com.quran.mobile.recitation.R
import com.quran.recitation.events.RecitationEventPresenter
import com.quran.recitation.tracking.FollowerConfig
import com.quran.recitation.tracking.LivePhonemeRecitation
import com.quran.recitation.tracking.PhonemeFollower
import com.quran.recitation.tracking.PhonemeRecognizerFactory
import com.quran.recitation.tracking.SuraInput
import com.quran.recitation.tracking.ZipformerModel
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A surah to carry on into: its words (appended to the progress), the page of each, and its openings. */
class NextSurah(
  val sura: Int,
  val words: List<MushafWord>,
  val pages: List<Int>,
  val openings: List<List<String>>,
)

/**
 * Hears the reader through the microphone and follows them on the mushaf, updating
 * [MushafRecitationState] and turning the pages. It is kept by the app rather than the reading
 * screen, so it carries on when the screen is rebuilt (turned between portrait and landscape).
 *
 * When a surah is finished the reader carries on into the next one: its words are appended to the
 * progress (so the finished surah keeps its colouring) and [base] marks where they begin, since the
 * follower numbers each surah's words from 0.
 */
@SingleIn(AppScope::class)
class MushafListening @Inject constructor(
  private val state: MushafRecitationState,
  private val eventPresenter: RecitationEventPresenter,
  private val recognizerFactory: PhonemeRecognizerFactory,
) {
  private val scope = MainScope()
  private var job: Job? = null

  // Where the current surah's words begin in the combined progress; the follower numbers each
  // surah's words from 0, so its word indices are shifted by this (see [apply] and [advance]).
  private var base = 0

  val isListening: Boolean get() = job?.isActive == true

  /** Called whenever listening stops, whether asked to or by itself (sura complete, an error). */
  var onStopped: (() -> Unit)? = null

  @SuppressLint("MissingPermission") // checked before starting
  fun start(
    context: Context,
    phonemes: List<String>,
    ayahs: List<Int>,
    startWord: Int,
    openings: List<List<String>>,
    from: SuraAyah,
    config: FollowerConfig,
    /** The surah to carry on into once a surah is finished, or null to stop there. */
    nextSurah: suspend (finishedSura: Int) -> NextSurah?,
  ) {
    val app = context.applicationContext
    base = 0
    state.setListening(true)
    eventPresenter.onListeningStateChange(true)
    eventPresenter.onRecitationChange(from)
    var shownAyah = from
    job = scope.launch {
      try {
        val recitation = LivePhonemeRecitation(
          recognizerFactory, ZipformerModel.file(app), ZipformerModel.tokens(app),
          phonemes, ayahs, startWord, openings, from.sura, config,
        )
        recitation.run(
          onListening = { toast(app, app.getString(R.string.recitation_listening, from.ayah)) },
          onUpdate = { update -> apply(update, shownAyah)?.let { shownAyah = it } },
          nextSura = { finished -> withContext(Dispatchers.Main) { nextSurah(finished)?.let { advance(it) } } },
        )
        // No more suras to follow: the reader finished.
        toast(app, app.getString(R.string.recitation_sura_complete))
        stop()
      } catch (e: Exception) {
        if (e !is CancellationException) {
          toast(app, app.getString(R.string.recitation_stopped, e.message))
          stop()
        }
      }
    }
  }

  fun stop() {
    val wasListening = job != null
    job?.cancel()
    job = null
    state.setListening(false)
    eventPresenter.onListeningStateChange(false)
    if (wasListening) onStopped?.invoke()
  }

  /**
   * Carries the progress on into [next]'s surah: appends its words (so the finished surah keeps its
   * colouring) and turns the page to it, and returns its words for the follower. Sets [base] to
   * where the surah begins in the combined progress, since the follower numbers its words from 0.
   */
  private fun advance(next: NextSurah): SuraInput {
    val start = state.progress.value?.words?.size ?: 0
    base = start
    state.update { progress ->
      progress.copy(
        words = progress.words + next.words,
        pages = progress.pages + next.pages,
        start = start,
        currentWord = start,
      )
    }
    eventPresenter.onRecitationChange(next.words.first().word.ayah)
    return SuraInput(
      next.sura,
      next.words.map { it.phonemes },
      next.words.map { it.word.ayah.ayah },
      0,
      next.openings,
    )
  }

  /** Applies [update]; returns the ayah the pages were turned to, if they were. */
  private fun apply(update: LivePhonemeRecitation.Update, shownAyah: SuraAyah): SuraAyah? {
    state.update { progress ->
      val recited = progress.recited.toHashSet()
      val missed = progress.missed.toHashSet()
      val wrongVowels = progress.wrongVowels.toHashSet()
      for (event in update.events) {
        // The follower numbers each surah's words from 0; shift them into the combined progress.
        when (event) {
          is PhonemeFollower.Event.Recited -> {
            val word = base + event.word
            recited += word
            missed -= word
            if (event.wrongVowels) wrongVowels += word else wrongVowels -= word
          }
          is PhonemeFollower.Event.Missed -> {
            val word = base + event.word
            missed += word
            recited -= word
            wrongVowels -= word
          }
        }
      }
      progress.copy(
        currentWord = if (update.currentWord >= 0) base + update.currentWord else progress.currentWord,
        recited = recited,
        missed = missed,
        wrongVowels = wrongVowels,
      )
    }
    val progress = state.progress.value ?: return null
    // Turn the page once the reader moves on to an ayah on the next one.
    val ayah = progress.currentAyah
    if (ayah != null && ayah != shownAyah) {
      eventPresenter.onRecitationChange(ayah)
      return ayah
    }
    return null
  }

  private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
  }
}
