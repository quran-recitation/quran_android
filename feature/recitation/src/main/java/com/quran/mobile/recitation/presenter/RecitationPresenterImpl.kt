package com.quran.mobile.recitation.presenter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import com.quran.data.core.QuranInfo
import com.quran.data.di.QuranScope
import com.quran.data.model.AyahWord
import com.quran.data.model.SuraAyah
import com.quran.mobile.recitation.R
import com.quran.recitation.common.Recitation
import com.quran.recitation.common.RecitationSession
import com.quran.recitation.common.RecitedAyah
import com.quran.recitation.events.RecitationEventPresenter
import com.quran.recitation.presenter.RecitationPresenter
import com.quran.recitation.tracking.FollowerConfig
import com.quran.recitation.tracking.PhonemeFollower
import com.quran.recitation.tracking.RecitationPhonemes
import com.quran.recitation.tracking.RecitationText
import com.quran.recitation.tracking.ZipformerModel
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Recitation on the mushaf pages: a streaming phoneme model (see ZipformerModel) recognizes what
 * is recited and a [PhonemeFollower] follows the reader through the sura word by word; the pages
 * show how far they got ([RecitationHighlightsPresenterImpl]) and turn as they move on.
 *
 * The microphone button starts from the selected ayah or the top of the page and stops again; the
 * session and its highlights stay until it is closed. Starting again on the page the reader
 * stopped on carries on from their ayah.
 */
@QuranScope
class RecitationPresenterImpl @Inject constructor(
  private val quranInfo: QuranInfo,
  private val recitationText: RecitationText,
  private val eventPresenter: RecitationEventPresenter,
  private val state: MushafRecitationState,
  private val listening: MushafListening,
) : RecitationPresenter {
  private val enabled = MutableStateFlow(true)
  private var activity: Activity? = null
  private var scope: CoroutineScope? = null
  private var preparing: Job? = null
  private var downloading: Job? = null
  private var pendingStart: SuraAyah? = null
  private var dialog: AlertDialog? = null
  private val onStopped: () -> Unit = { onListeningStopped() }

  private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
    // The microphone is only heard while the app is in the foreground (and carries on while the
    // screen is rebuilt, turned between portrait and landscape).
    override fun onActivityStopped(activity: Activity) {
      if (activity === this@RecitationPresenterImpl.activity && !activity.isChangingConfigurations) {
        stopListening()
      }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
  }

  override fun bind(what: Activity) {
    activity = what
    scope = MainScope()
    what.application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    state.restoreHideUnrecited(
      what.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_HIDE, false)
    )
    // Still listening from before the screen was rebuilt: say so again for the new screen.
    if (listening.isListening) {
      what.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      eventPresenter.onListeningStateChange(true)
    }
    listening.onStopped = onStopped
  }

  override fun unbind(what: Activity) {
    if (activity !== what) return
    if (!what.isChangingConfigurations) stopListening()
    if (listening.onStopped === onStopped) listening.onStopped = null
    dialog?.dismiss()
    dialog = null
    what.application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
    scope?.cancel()
    scope = null
    downloading = null
    activity = null
  }

  override fun isRecitationEnabled(): Boolean = true
  override fun isRecitationEnabledFlow(): StateFlow<Boolean> = enabled

  override fun startOrStopRecitation(startAyah: () -> SuraAyah) {
    val activity = activity ?: return
    if (listening.isListening) {
      stopListening()
      return
    }
    if (preparing != null || downloading != null) return
    val ayah = startAyah()
    when {
      !ZipformerModel.isDownloaded(activity) -> offerDownload(activity, ayah)
      activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
        pendingStart = ayah
        activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
      }
      else -> start(activity, ayah)
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
    if (requestCode != REQUEST_AUDIO) return
    val ayah = pendingStart ?: return
    pendingStart = null
    val activity = activity ?: return
    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
      start(activity, ayah)
    } else {
      toast(activity, activity.getString(R.string.recitation_microphone_needed))
    }
  }

  override fun endSession() {
    stopListening()
    state.clear()
    eventPresenter.onRecitationSessionChange(null)
  }

  private fun offerDownload(activity: Activity, ayah: SuraAyah) {
    dialog = AlertDialog.Builder(activity)
      .setTitle(R.string.recitation_model_title)
      .setMessage(
        activity.getString(
          R.string.recitation_model_offer,
          // in the app's language's digits, like the download's progress
          activity.getString(R.string.recitation_model_megabytes, ZipformerModel.DOWNLOAD_MEGABYTES),
        )
      )
      .setPositiveButton(R.string.recitation_model_download) { _, _ -> download(activity, ayah) }
      .setNegativeButton(R.string.recitation_cancel, null)
      .show()
  }

  private fun download(activity: Activity, ayah: SuraAyah) {
    val scope = scope ?: return
    val progress = AlertDialog.Builder(activity)
      .setTitle(R.string.recitation_model_downloading)
      .setMessage(activity.getString(R.string.recitation_model_starting))
      .setCancelable(false)
      .setNegativeButton(R.string.recitation_cancel) { _, _ ->
        downloading?.cancel()
        downloading = null
      }
      .show()
    dialog = progress
    downloading = scope.launch {
      val failure = try {
        ZipformerModel.download(activity) { percent, bytes ->
          progress.setMessage(
            if (percent >= 0) {
              activity.getString(R.string.recitation_model_percent, percent.toInt())
            } else {
              activity.getString(R.string.recitation_model_megabytes, (bytes / 1_000_000).toInt())
            }
          )
        }
        null
      } catch (e: Exception) {
        e
      }
      // Cancelled from the dialog (which closes itself) or because the screen is closing.
      if (failure is CancellationException) throw failure
      progress.dismiss()
      downloading = null
      if (failure == null) {
        startOrStopRecitation({ ayah })
      } else {
        toast(activity, activity.getString(R.string.recitation_model_failed, failure.message))
      }
    }
  }

  private fun start(activity: Activity, requested: SuraAyah) {
    val scope = scope ?: return
    preparing = scope.launch {
      val progress = state.progress.value?.takeIf {
        eventPresenter.hasRecitationSession() && it.sura == requested.sura
      } ?: newSession(activity, requested) ?: run {
        toast(activity, activity.getString(R.string.recitation_no_arabic))
        preparing = null
        return@launch
      }
      // Carry on from the ayah the reader stopped on when they start again on that page;
      // otherwise start at the requested ayah. Highlights from there on start over.
      val current = progress.currentAyah
      val from = if (current != null && requested <= current &&
        quranInfo.getPageFromSuraAyah(current.sura, current.ayah) ==
        quranInfo.getPageFromSuraAyah(requested.sura, requested.ayah)
      ) current else requested
      val startWord = progress.indexOf(from).coerceAtLeast(0)
      state.update { p ->
        p.copy(
          start = startWord,
          currentWord = startWord,
          recited = p.recited.filterTo(HashSet()) { it < startWord },
          missed = p.missed.filterTo(HashSet()) { it < startWord },
          wrongVowels = p.wrongVowels.filterTo(HashSet()) { it < startWord },
        )
      }
      val openings = RecitationPhonemes.openings(activity, from.sura)
      preparing = null
      listen(
        activity, progress.words.map { it.phonemes }, progress.words.map { it.word.ayah.ayah },
        startWord, openings, from,
      )
    }
  }

  /** Starts a session on [start]'s sura; null when its text is not available. */
  private suspend fun newSession(context: Context, start: SuraAyah): MushafRecitationProgress? {
    val (words, pages) = surahContent(context, start.sura) ?: return null
    val first = words.indexOfFirst { it.word.ayah == start }.coerceAtLeast(0)
    state.start(MushafRecitationProgress(words, pages, start = first, currentWord = first))
    eventPresenter.onRecitationSessionChange(MushafSession(start, state))
    return state.progress.value
  }

  /** [sura]'s words (phonemes with their mushaf positions) and the page of each; null with no text. */
  private suspend fun surahContent(context: Context, sura: Int): Pair<List<MushafWord>, List<Int>>? {
    val phonemes = RecitationPhonemes.sura(context, sura)
    // Mushaf word positions count the ۞ and ۩ marks between words, which are not recited.
    val positions = recitationText.words(sura, 1)
      .filter { word -> word.text.any { it in 'ء'..'ي' || it == 'ٱ' } }
      .groupBy({ it.word.ayah.ayah }, { it.word.wordPosition })
    if (phonemes.isEmpty() || positions.isEmpty()) return null
    val words = phonemes.flatMapIndexed { index, ayahPhonemes ->
      val ayah = SuraAyah(sura, index + 1)
      val ayahPositions = positions[ayah.ayah].orEmpty()
      ayahPhonemes.mapIndexed { i, phoneme ->
        val position = if (ayahPositions.size == ayahPhonemes.size) ayahPositions[i] else i + 1
        MushafWord(AyahWord(ayah, position), phoneme)
      }
    }
    val pages = words.map { quranInfo.getPageFromSuraAyah(it.word.ayah.sura, it.word.ayah.ayah) }
    return words to pages
  }

  private fun listen(
    activity: Activity,
    phonemes: List<String>,
    ayahs: List<Int>,
    startWord: Int,
    openings: List<List<String>>,
    from: SuraAyah,
  ) {
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    // The application context, not the activity, so carrying on into the next surah still works
    // when the reading screen is rebuilt (e.g. the screen is rotated) mid-recitation, which unbinds
    // this activity-scoped presenter; its data dependencies (text, QuranInfo) are app-scoped.
    val appContext = activity.applicationContext
    listening.start(
      activity, phonemes, ayahs, startWord, openings, from, FollowerConfig(),
    ) { finishedSura ->
      nextSurah(appContext, finishedSura)
    }
  }

  /**
   * The surah to carry straight on into once [finishedSura] is done, so the reader is not stopped
   * at a surah's end; null at the end of the mushaf or when the surah's text is unavailable, which
   * stops the session. [MushafListening] appends it to the progress, keeping the finished surah's
   * colouring. Uses the application [context] so it survives the reading screen being rebuilt.
   */
  private suspend fun nextSurah(context: Context, finishedSura: Int): NextSurah? {
    val next = finishedSura + 1
    if (next > LAST_SURA) return null
    val (words, pages) = surahContent(context, next) ?: return null
    val openings = RecitationPhonemes.openings(context, next)
    return NextSurah(next, words, pages, openings)
  }

  private fun stopListening() {
    preparing?.cancel()
    preparing = null
    listening.stop()
  }

  private fun onListeningStopped() {
    val activity = activity ?: return
    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    // Whether unrecited words are hidden, for the next time.
    activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putBoolean(KEY_HIDE, state.hidesUnrecited).apply()
  }

  private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
  }

  private class MushafSession(
    private val start: SuraAyah,
    private val state: MushafRecitationState,
  ) : RecitationSession {
    private val sessionId = UUID.randomUUID().toString()
    private val startedAt = System.currentTimeMillis()

    override fun id(): String = sessionId
    override fun startAyah(): SuraAyah = start
    override fun currentAyah(): SuraAyah = state.progress.value?.currentAyah ?: start
    override fun startedAt(): Long = startedAt
    override fun recitation(): Recitation = object : Recitation {
      override fun recitedAyahs(): List<RecitedAyah> = emptyList()
    }
  }

  private companion object {
    const val REQUEST_AUDIO = 4201
    const val PREFERENCES = "recitation_mushaf"
    const val KEY_HIDE = "hide_unrecited"
    const val LAST_SURA = 114
  }
}
