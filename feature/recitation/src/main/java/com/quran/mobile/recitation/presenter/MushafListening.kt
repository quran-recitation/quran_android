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

/**
 * Hears the reader through the microphone and follows them on the mushaf, updating
 * [MushafRecitationState] and turning the pages. It is kept by the app rather than the reading
 * screen, so it carries on when the screen is rebuilt (turned between portrait and landscape).
 */
@SingleIn(AppScope::class)
class MushafListening @Inject constructor(
  private val state: MushafRecitationState,
  private val eventPresenter: RecitationEventPresenter,
  private val recognizerFactory: PhonemeRecognizerFactory,
) {
  private val scope = MainScope()
  private var job: Job? = null

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
    /** The sura to follow once a sura is finished (the reader carries on), or null to stop there. */
    nextSura: suspend (finishedSura: Int) -> SuraInput?,
  ) {
    val app = context.applicationContext
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
          nextSura = { finished -> withContext(Dispatchers.Main) { nextSura(finished) } },
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

  /** Applies [update]; returns the ayah the pages were turned to, if they were. */
  private fun apply(update: LivePhonemeRecitation.Update, shownAyah: SuraAyah): SuraAyah? {
    state.update { progress ->
      val recited = progress.recited.toHashSet()
      val missed = progress.missed.toHashSet()
      val wrongVowels = progress.wrongVowels.toHashSet()
      for (event in update.events) {
        when (event) {
          is PhonemeFollower.Event.Recited -> {
            recited += event.word
            missed -= event.word
            if (event.wrongVowels) wrongVowels += event.word else wrongVowels -= event.word
          }
          is PhonemeFollower.Event.Missed -> {
            missed += event.word
            recited -= event.word
            wrongVowels -= event.word
          }
        }
      }
      progress.copy(
        currentWord = if (update.currentWord >= 0) update.currentWord else progress.currentWord,
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
