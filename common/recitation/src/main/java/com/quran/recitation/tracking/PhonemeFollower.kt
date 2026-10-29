package com.quran.recitation.tracking

import kotlin.math.min

/** Costs and thresholds of [PhonemeFollower]. Costs are per phoneme, 1 being a plain mistake. */
data class FollowerConfig(
  /** Per phoneme heard before the reader starts (isti'adha, basmala, noise). */
  val noiseCost: Double = 0.6,
  /** An extra phoneme heard. */
  val insertionCost: Double = 1.0,
  /** The same phoneme heard again (a prolonged sound). */
  val repeatInsertionCost: Double = 0.3,
  /** An expected phoneme not heard. */
  val deletionCost: Double = 1.0,
  /** An expected phoneme repeating the one before it not heard (madd, shadda, ghunna lengths). */
  val repeatDeletionCost: Double = 0.3,
  /** A phoneme heard as a different one. */
  val substitutionCost: Double = 1.0,
  /** A phoneme heard as one that sounds alike (a long and short vowel, emphatic pairs). */
  val similarCost: Double = 0.5,
  /** Going back to the start of an earlier word (repeating). */
  val jumpBackCost: Double = 6.0,
  /** Going ahead to the start of a later word, past words not heard (skipping). */
  val jumpAheadCost: Double = 12.0,
  /** Words passed at once beyond which they count as skipped rather than missed. */
  val maxMissedRun: Int = 3,
  /** Words the reader must go back for it to count as reciting them again. */
  val minRepeat: Int = 2,
  /** Average cost per phoneme up to which a word counts as recited. */
  val maxWordCost: Double = 0.35,
  /**
   * Share of an ayah's words missed at which the ayah counts as wrong (skipped, or recited from
   * elsewhere): the reader is held there and expected to recite it again. The mushaf shows such an
   * ayah in red, so this must match the highlights' incorrect-ayah fraction.
   */
  val incorrectAyahFraction: Double = 0.30,
)

/**
 * Follows a reader through a sura word by word from the phonemes a streaming recognizer hears
 * (Quran-Lab's zipformer_p-arabic-v3, version 3.1, run by sherpa-onnx; the phonemes of quran-transcript).
 *
 * Every phoneme heard updates an edit-distance alignment of everything heard so far against the
 * sura's phonemes: one cost per position in the sura (the phonemes recited up to there). The
 * reader is at the cheapest position. Before they start, phonemes are noise or one of the
 * [openings] (isti'adha, basmala), which they may say or skip; from any position
 * they may go back to the start of an earlier word or ahead to the start of a later one, at a
 * fixed cost, so repeats and skips are followed. They are not followed back before [startWord]
 * (the ayah they chose to begin at), so reciting an earlier ayah does not pull the session back.
 *
 * When the reader moves past a word, it is checked against what was heard while they were on it:
 * close enough and it is [Event.Recited] (noting any letter heard with another vowel than its own),
 * otherwise [Event.Missed]. Words passed in a jump ahead are missed, apart from the last few before
 * it, which the reader may already have said.
 *
 * Ayahs must be recited in order: words are marked from the one expected next ([currentWord]) on,
 * as the reader passes it. A slip within an ayah is a missed word, but an ayah left with at least
 * [FollowerConfig.incorrectAyahFraction] of its words missed (skipped, or recited from somewhere
 * else) is missed from its first missed word on and expected again from there: words after it are
 * followed but not marked until the reader comes back and recites it. So is the rest of an ayah
 * the reader says more than is left of
 * without making progress (another passage altogether). Going back to earlier words (repeating)
 * clears nothing; one missed turns recited when said right.
 */
class PhonemeFollower(
  private val words: List<String>,
  /** The ayah of each word. */
  ayahs: List<Int>,
  private val startWord: Int,
  openings: List<List<String>> = emptyList(),
  private val config: FollowerConfig = FollowerConfig(),
) {

  sealed class Event {
    /**
     * The reader recited [word]; [wrongVowels] when a letter of it was heard with another vowel
     * (fatha, damma, kasra) than its own, or with none or one it does not have.
     */
    data class Recited(val word: Int, val wrongVowels: Boolean = false) : Event()

    /** The reader passed [word] without saying it right. */
    data class Missed(val word: Int) : Event()
  }

  // Where each word's ayah ends (the index after its last word), and its sounds: phonemes, a
  // phoneme lengthened (repeated) counting once.
  private val ayahEnds = IntArray(words.size).also { ends ->
    for (i in words.indices.reversed()) {
      ends[i] = if (i + 1 < words.size && ayahs[i + 1] == ayahs[i]) ends[i + 1] else i + 1
    }
  }
  private val wordSounds = IntArray(words.size) { sounds(words[it]) }

  // The openings, then the sura; positions in the openings are before the reader starts.
  private val openingPhrases = openings.map { it.joinToString("") }.filter { it.isNotEmpty() }
  private val suraOffset = openingPhrases.sumOf { it.length }
  private val reference: String = openingPhrases.joinToString("") + words.joinToString("")
  private val wordStarts = IntArray(words.size + 1).also { starts ->
    var offset = suraOffset
    words.forEachIndexed { i, w ->
      starts[i] = offset
      offset += w.length
    }
    starts[words.size] = offset
  }
  private val positions = reference.length + 1

  private var cost = DoubleArray(positions) { INFINITY }
  private var next = DoubleArray(positions)
  private var noise = 0.0
  // The word the reader chose to begin at; they are not followed back before it.
  private val startFloor = startWord.coerceIn(0, words.size)
  private val startPosition = wordStarts[startFloor]

  // Where an opening phrase starts and ends; after one, the reader may say a later one or start.
  private val phraseStarts = IntArray(openingPhrases.size)
  private val phraseEnds = IntArray(openingPhrases.size)

  init {
    var offset = 0
    openingPhrases.forEachIndexed { i, phrase ->
      phraseStarts[i] = offset
      offset += phrase.length
      phraseEnds[i] = offset
    }
  }

  // What was heard, from absolute index `heardOffset`.
  private val heard = StringBuilder()
  private var heardOffset = 0
  private var heardCount = 0
  private var lastHeard = 0.toChar()
  /** Sounds heard: phonemes, a phoneme lengthened (repeated) counting once. */
  private var soundsHeard = 0

  // The recognizer's tokens already taken in.
  private var tokensTaken = 0

  /**
   * The word expected next (-1 before the reader starts): the first not yet recited in order. It
   * only goes forward as the reader recites it (and the words after it), or back to the first
   * missed word of an ayah they skipped or recited from elsewhere, which they must come back to.
   */
  var currentWord = -1
    private set

  /** Where the reader is: the word whose next phoneme is expected (-1 before they start). */
  private var position = -1

  // Since the reader last made progress (recited the expected word, or a few earlier ones in a
  // row): sounds heard then, and whether the rest of the expected word's ayah was missed since.
  private var progressAt = 0
  private var stuck = false
  /** Earlier words passed and recited again in a row (a repeat, rather than chance matches). */
  private var againInRow = 0

  // The words of an ayah marked in order: the ayah's end, how many and how many missed, and the
  // first missed.
  private var passEnd = -1
  private var passWords = 0
  private var passMissed = 0
  private var passFirstMissed = -1

  /** When the reader came to each word they are on or passed in this attempt (heard index). */
  private val reachedAt = HashMap<Int, Int>()

  private val events = ArrayList<Event>()

  // When the last word recited was (heard index).
  private var lastRecitedAt = 0

  /**
   * Takes all tokens the recognizer produced since it was last reset; returns what happened since
   * the previous call.
   */
  fun accept(tokens: List<String>): List<Event> {
    if (tokens.size < tokensTaken) tokensTaken = 0 // the recognizer started over
    for (i in tokensTaken until tokens.size) {
      val token = tokens[i]
      if (token.startsWith("<")) continue // blank and other special tokens
      for (c in token) hear(c)
    }
    tokensTaken = tokens.size
    update()
    return events.toList().also { events.clear() }
  }

  /** After the recognizer was reset: its tokens start over. */
  fun onRecognizerReset() {
    tokensTaken = 0
  }

  private fun hear(c: Char) {
    val insertion = if (c == lastHeard) config.repeatInsertionCost else config.insertionCost
    if (c != lastHeard) soundsHeard++
    lastHeard = c
    heard.append(c)
    heardCount++

    // Before the reader starts, they may say an opening or start at the start word at any time.
    cost[startPosition] = min(cost[startPosition], noise)
    for (p in phraseStarts) cost[p] = min(cost[p], noise)
    noise += config.noiseCost

    val prev = cost
    val now = next
    now[0] = prev[0] + insertion
    for (j in 1 until positions) {
      val inserted = prev[j] + insertion
      val matched = prev[j - 1] + substitution(c, reference[j - 1])
      now[j] = if (matched <= inserted) matched else inserted
    }
    jumps(now)
    for (j in 1 until positions) {
      val deleted = now[j - 1] + deletion(j - 1)
      if (deleted < now[j]) now[j] = deleted
    }
    // After an opening: a later opening or the start word.
    for (i in phraseEnds.indices) {
      val done = now[phraseEnds[i]]
      for (k in i + 1 until phraseStarts.size) if (done < now[phraseStarts[k]]) now[phraseStarts[k]] = done
      if (done < now[startPosition]) now[startPosition] = done
    }
    // Keep costs small; only differences matter.
    var lowest = noise
    for (value in now) if (value < lowest) lowest = value
    for (j in now.indices) now[j] -= lowest
    noise -= lowest
    cost = now
    next = prev
  }

  /** Going back to an earlier word's start, or ahead to a later one's (in the sura). */
  private fun jumps(now: DoubleArray) {
    // ahead: the cheapest position before each word start
    var before = INFINITY
    var w = 0
    for (j in suraOffset until positions) {
      if (w < words.size && j == wordStarts[w]) {
        // Not before the start word: the reader can't be placed before the ayah they chose, so an
        // earlier ayah (ahead of the openings but behind the start) is not jumped into either.
        if (w >= startFloor) {
          val jumped = before + config.jumpAheadCost
          if (jumped < now[j]) now[j] = jumped
        }
        w++
      }
      if (now[j] < before) before = now[j]
    }
    // back: the cheapest position after each word start
    var after = INFINITY
    w = words.size - 1
    for (j in positions - 1 downTo suraOffset) {
      if (w >= 0 && j == wordStarts[w]) {
        // Not before the word the reader chose to begin at: a selected ayah is a hard start, so
        // reciting an earlier ayah is not followed back there.
        if (w >= startFloor) {
          val jumped = after + config.jumpBackCost
          if (jumped < now[j]) now[j] = jumped
        }
        w--
      }
      if (now[j] < after) after = now[j]
    }
  }

  private fun update() {
    var best = 0
    for (j in 1 until positions) if (cost[j] <= cost[best]) best = j // ties: further on
    if (noise < cost[best] || best < suraOffset) return // not started yet (or saying an opening)
    val word = wordAt(best)
    if (position < 0) {
      // On starting, the words from the start word on were recited since the beginning.
      position = startWord
      currentWord = startWord
      reachedAt[startWord] = heardOffset
      progressAt = soundsHeard
    }
    val previous = position
    position = word
    when {
      word > previous -> advance(previous, word)
      word <= previous - config.minRepeat -> goBack(word)
      else -> reachedAt.putIfAbsent(word, heardCount)
    }
    missIfStuck()
    trimHeard()
  }

  /**
   * The reader went on from [previous] to [word]: marks the expected word and the ones after it
   * that they passed, in order. Earlier words passed again (the reader repeating) can only turn
   * recited; later ones are not marked until the reader recites the expected one.
   */
  private fun advance(previous: Int, word: Int) {
    val jump = word - previous > config.maxMissedRun
    // After a jump ahead, the reader may already have said the last few words before it was clear;
    // only what was heard after the last word recited counts (which may sound like these).
    val since = lastRecitedAt
    for (w in previous until word) {
      if (w > currentWord) break
      val reached = reachedAt[w]
      val recited = when {
        !jump -> check(w, reached)
        w >= word - JUMP_LOOKBACK -> check(w, since, margin = 0)
        else -> null
      }
      reachedAt.remove(w)
      if (w < currentWord) {
        if (recited != null) {
          recitedAgain(recited)
          if (++againInRow >= REPEAT_PROGRESS) progressAt = soundsHeard
        } else {
          againInRow = 0
        }
      } else {
        // Once the ayah was found recited from elsewhere, a word missed is a slip only when the
        // reader is heard going on with the next one; otherwise they are still elsewhere.
        if (recited == null && stuck && (w + 1 >= ayahEnds[w] || check(w + 1, reached) == null)) break
        if (recited != null) recited(recited) else missed(w)
        moveOn(w + 1, progress = recited != null)
        if (w + 1 == ayahEnds[w]) leftAyah()
      }
    }
    if (jump) reachedAt.clear()
    reachedAt[word] = heardCount
  }

  /** The reader went back to [word], to recite earlier words again. */
  private fun goBack(word: Int) {
    againInRow = 0
    // The jump back is clear only some way into the repeat (repeated passages often start
    // alike): the last few words before it may already have been said again. Not before the start
    // word, though: words earlier than the ayah the reader chose are never marked.
    val from = maxOf(startFloor, word - JUMP_LOOKBACK)
    val recent = (from until word).sumOf { words[it].length } * 2 + MARGIN
    for (w in from until minOf(word, currentWord)) {
      check(w, heardCount - recent, margin = 0)?.let(::recitedAgain)
    }
    // Back into the expected word's ayah, a little after it: the words between are marked, in order.
    if (currentWord in 0..word && word < ayahEnds[currentWord]) {
      for (w in currentWord until word) {
        val recited = if (w >= from) check(w, heardCount - recent, margin = 0) else null
        if (recited != null) recited(recited) else missed(w)
        moveOn(w + 1, progress = recited != null)
      }
    }
    reachedAt.clear()
    reachedAt[word] = heardCount
  }

  private fun recited(event: Event.Recited) {
    events += event
    lastRecitedAt = heardCount
    counted(event.word, missed = false)
  }

  /** An earlier word recited again: right this time, if it was missed. */
  private fun recitedAgain(event: Event.Recited) {
    events += event
    lastRecitedAt = heardCount
  }

  private fun missed(word: Int) {
    events += Event.Missed(word)
    counted(word, missed = true)
  }

  private fun counted(word: Int, missed: Boolean) {
    if (ayahEnds[word] != passEnd) {
      passEnd = ayahEnds[word]
      passWords = 0
      passMissed = 0
      passFirstMissed = -1
    }
    passWords++
    if (missed) {
      passMissed++
      if (passFirstMissed < 0) passFirstMissed = word
    }
  }

  /** The word expected next is now [word]; [progress] when the reader recited the one before. */
  private fun moveOn(word: Int, progress: Boolean) {
    if (progress) progressAt = soundsHeard
    if (progress || word >= words.size || currentWord !in words.indices ||
      ayahEnds[word] != ayahEnds[currentWord]
    ) {
      stuck = false
    }
    currentWord = word
  }

  /**
   * The reader passed the end of the ayah of the words last marked: with at least
   * [FollowerConfig.incorrectAyahFraction] of them missed, it was skipped (or recited from
   * elsewhere), and is expected again from its first missed word.
   */
  private fun leftAyah() {
    if (passEnd < 0) return
    if (passMissed >= passWords * config.incorrectAyahFraction) {
      for (w in passFirstMissed until passEnd) events += Event.Missed(w)
      currentWord = passFirstMissed
      stuck = true // missed already
    }
    passEnd = -1
  }

  /**
   * When the reader has said more than is left of the expected word's ayah (by [STUCK_RATIO], and
   * [STUCK_MARGIN] sounds), or [STUCK_MAX] sounds, without reciting a word, they are reciting
   * something else: the ayah is
   * missed from its first word missed so far (or the expected one) on, and expected again from
   * there.
   */
  private fun missIfStuck() {
    val word = currentWord
    if (stuck || word < 0 || word >= words.size) return
    val left = (word until ayahEnds[word]).sumOf { wordSounds[it] }
    if (soundsHeard - progressAt > minOf(STUCK_RATIO * left + STUCK_MARGIN, STUCK_MAX.toDouble())) {
      val from = if (passEnd == ayahEnds[word] && passFirstMissed in 0..word) passFirstMissed else word
      for (w in from until ayahEnds[word]) events += Event.Missed(w)
      currentWord = from
      stuck = true
    }
  }

  /** The word whose next phoneme is expected at sura [position] (words.size after the last). */
  private fun wordAt(position: Int): Int {
    var low = 0
    var high = words.size
    while (low < high) {
      val mid = (low + high) ushr 1
      if (wordStarts[mid + 1] <= position) low = mid + 1 else high = mid
    }
    return low
  }

  /**
   * [word] as recited if what was heard since [reached] (when the reader came to it, less [margin]
   * phonemes) contains it, close enough; null if not.
   */
  private fun check(word: Int, reached: Int?, margin: Int = MARGIN): Event.Recited? {
    val expected = words[word]
    if (expected.isEmpty()) return Event.Recited(word)
    val from = maxOf(heardOffset, (reached ?: heardCount) - margin)
    val alignment = align(heard.substring(from - heardOffset), word)
    if (alignment.cost / expected.length > config.maxWordCost) return null
    return Event.Recited(word, wrongVowels = alignment.hasWrongVowel(word))
  }

  /**
   * What was heard for each phoneme of a word: the index in the heard span of the phoneme it was
   * heard as (-1 if not heard), and the cost of that alignment.
   */
  private class Alignment(val span: String, val heardAs: IntArray, val cost: Double)

  /**
   * The cheapest alignment of [word]'s phonemes to part of [span] (anything before and after it
   * free), with the same costs as following.
   */
  private fun align(span: String, word: Int): Alignment {
    val start = wordStarts[word]
    val n = words[word].length
    val m = span.length
    // costs[i][k]: the first k phonemes of the word aligned to heard phonemes ending at i
    val costs = Array(m + 1) { DoubleArray(n + 1) }
    for (k in 1..n) costs[0][k] = costs[0][k - 1] + deletion(start + k - 1)
    for (i in 1..m) {
      val c = span[i - 1]
      val insertion = if (i > 1 && c == span[i - 2]) config.repeatInsertionCost else config.insertionCost
      costs[i][0] = 0.0 // free start
      for (k in 1..n) {
        costs[i][k] = min(
          min(costs[i - 1][k] + insertion, costs[i - 1][k - 1] + substitution(c, reference[start + k - 1])),
          costs[i][k - 1] + deletion(start + k - 1),
        )
      }
    }
    var end = 0 // free end
    for (i in 1..m) if (costs[i][n] < costs[end][n]) end = i
    val heardAs = IntArray(n) { -1 }
    var i = end
    var k = n
    while (k > 0) {
      val here = costs[i][k]
      when {
        i > 0 && here == costs[i - 1][k - 1] + substitution(span[i - 1], reference[start + k - 1]) -> {
          heardAs[k - 1] = i - 1
          i--
          k--
        }
        here == costs[i][k - 1] + deletion(start + k - 1) -> k--
        else -> i--
      }
    }
    return Alignment(span, heardAs, costs[end][n])
  }

  /**
   * Whether a letter of [word] heard as itself (or one alike) was heard with another vowel than
   * its own: the short vowels heard from it up to the next letter heard, or the long vowel heard
   * in their place. Long vowels and their lengths are left to the alignment cost, and a last
   * letter heard without its vowel is a pause (waqf), not a mistake.
   */
  private fun Alignment.hasWrongVowel(word: Int): Boolean {
    val phonemes = words[word]
    val letters = phonemes.indices.filter { !isShortVowel(phonemes[it]) }
    letters.forEachIndexed { n, k ->
      val at = heardAs[k]
      if (at < 0 || phonemes[k] in LONG_VOWELS) return@forEachIndexed
      if (substitution(span[at], phonemes[k]) == config.substitutionCost) return@forEachIndexed
      val expected = if (k + 1 < phonemes.length && isShortVowel(phonemes[k + 1])) phonemes[k + 1] else null
      // A letter without a vowel that is doubled (shadda, or merged into the next word's first
      // letter) may be heard once, with its twin's vowel.
      val twin = letters.getOrNull(n + 1)?.let { canonical(phonemes[it]) == canonical(phonemes[k]) } ?: true
      if (expected == null && twin) return@forEachIndexed
      // Up to the next letter heard (or the next one's place); letters repeated for a shadda
      // or ghunna are part of this one.
      val nextLetter = letters.drop(n + 1).firstOrNull { heardAs[it] >= 0 }
      val until = nextLetter?.let { heardAs[it] } ?: minOf(span.length, at + 3)
      var vowel: Char? = null
      for (j in at + 1 until until) {
        val c = span[j]
        if (isShortVowel(c)) { vowel = c; break }
        if (c in LONG_VOWELS) { vowel = LONG_VOWELS.getValue(c); break }
        if (canonical(c) != canonical(span[at])) break
      }
      val last = letters.last() == k
      if (vowel != expected && !(last && vowel == null)) return true
    }
    return false
  }

  private fun trimHeard() {
    val keepFrom = (reachedAt.values.minOrNull() ?: heardCount) - MARGIN
    val trim = keepFrom - heardOffset
    if (trim > KEEP) {
      heard.delete(0, trim)
      heardOffset += trim
    }
  }

  private fun deletion(refIndex: Int): Double {
    val c = reference[refIndex]
    return when {
      c == QALQALA -> config.repeatDeletionCost
      refIndex > 0 && reference[refIndex - 1] == c -> config.repeatDeletionCost
      else -> config.deletionCost
    }
  }

  private fun substitution(heard: Char, expected: Char): Double = when {
    heard == expected || canonical(heard) == canonical(expected) -> 0.0
    similar(heard, expected) -> config.similarCost
    else -> config.substitutionCost
  }

  private companion object {
    const val INFINITY = 1e18
    /** Phonemes heard before the reader came to a word that may still be part of it. */
    const val MARGIN = 8
    /** Words before the reader's position checked after a jump ahead. */
    const val JUMP_LOOKBACK = 4
    /** Heard phonemes no longer needed are dropped in batches of this many. */
    const val KEEP = 256
    const val QALQALA = 'ڇ' // ڇ
    /** Sounds said beyond what is left of the ayah the reader is on before they are held there. */
    const val STUCK_RATIO = 1.3
    const val STUCK_MARGIN = 8
    /** Sounds said without reciting a word (some five words) before the reader is held, at most. */
    const val STUCK_MAX = 40
    /** Earlier words recited again in a row for a repeat to count as progress. */
    const val REPEAT_PROGRESS = 3

    /** Phonemes in [text], a phoneme repeated (lengthened) counting once. */
    fun sounds(text: String): Int = text.indices.count { it == 0 || text[it] != text[it - 1] }

    fun isShortVowel(c: Char) = c == 'َ' || c == 'ُ' || c == 'ِ' // fatha, damma, kasra

    /** Long vowels, by the short vowel they lengthen. */
    val LONG_VOWELS = mapOf('ا' to 'َ', 'ۥ' to 'ُ', 'ۦ' to 'ِ')

    /** Glyphs of the phonetic script for the same sound. */
    fun canonical(c: Char): Char = when (c) {
      'ۦ' -> 'ي' // ۦ ي
      'ۥ' -> 'و' // ۥ و
      'ں' -> 'ن' // ں ن
      '۾' -> 'م' // ۾ م
      'أ', 'إ', 'آ', 'ٲ' -> 'ء' // hamza forms
      else -> c
    }

    private val SIMILAR = listOf(
      "َا", // fatha, alif
      "ُو", // damma, waw
      "ِي", // kasra, ya
      "سص", // س ص
      "تط", // ت ط
      "دض", // د ض
      "ذظز", // ذ ظ ز
      "قك", // ق ك
      "هح", // ه ح
      "ءع", // ء ع
    ).map { group -> group.map(::canonical).toSet() }

    fun similar(a: Char, b: Char): Boolean {
      val x = canonical(a)
      val y = canonical(b)
      return SIMILAR.any { x in it && y in it }
    }
  }
}
