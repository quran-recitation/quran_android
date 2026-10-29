package com.quran.recitation.tracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Replays what the recognizer heard of Minshawi's recitation (tools/recitation-phonemes/
 * export_fixture.py) through the follower.
 */
class PhonemeFollowerTest {

  private val phonemes: Map<String, List<String>> =
    File("src/main/assets/recitation/phonemes.txt").readLines()
      .filter { it.isNotBlank() && !it.startsWith("#") }
      .associate { line ->
        val (key, words) = line.split('\t', limit = 2)
        key to words.split(' ')
      }

  private class Replay(val ayahOf: List<Int>) {
    val recited = sortedSetOf<Int>()
    val missed = sortedSetOf<Int>()
    val everMissed = sortedSetOf<Int>()
    val wrongVowels = sortedSetOf<Int>()
    /** The word expected next after each chunk of audio. */
    val expected = ArrayList<Int>()
    /** The words recited, in the order they were marked. */
    val recitedOrder = ArrayList<Int>()
    var currentWord = -1

    fun ayahWords(ayah: Int) = ayahOf.indices.filter { ayahOf[it] == ayah }
  }

  private fun replay(fixture: String): Replay {
    val lines = requireNotNull(javaClass.classLoader?.getResourceAsStream("zipformer/$fixture")) { fixture }
      .bufferedReader().readLines()
    val (sura, firstAyah) = lines[0].split(' ').map { it.toInt() }
    val ayahs = generateSequence(1) { it + 1 }.map { phonemes["$sura:$it"] }.takeWhile { it != null }.filterNotNull().toList()
    val replay = Replay(ayahs.flatMapIndexed { i, words -> List(words.size) { i + 1 } })
    val openings = listOfNotNull(phonemes["istiadha"], phonemes["basmala"]?.takeIf { sura != 1 && sura != 9 })
    val follower = PhonemeFollower(ayahs.flatten(), replay.ayahOf, replay.ayahOf.indexOf(firstAyah), openings)
    var tokens = listOf<String>()
    for (line in lines.drop(1)) {
      val parts = line.split(' ')
      tokens = tokens.take(parts[0].toInt()) + parts.drop(1).filter { it.isNotEmpty() }
      for (event in follower.accept(tokens)) {
        when (event) {
          is PhonemeFollower.Event.Recited -> {
            replay.recited += event.word
            replay.recitedOrder += event.word
            replay.missed -= event.word
            if (event.wrongVowels) replay.wrongVowels += event.word else replay.wrongVowels -= event.word
          }
          is PhonemeFollower.Event.Missed -> {
            replay.missed += event.word
            replay.everMissed += event.word
            replay.recited -= event.word
            replay.wrongVowels -= event.word
          }
        }
      }
      replay.expected += follower.currentWord
    }
    replay.currentWord = follower.currentWord
    return replay
  }

  @Test
  fun `follows al-Fatiha to its end`() {
    val replay = replay("fatiha.txt")
    assertThat(replay.recited).containsExactlyElementsIn(replay.ayahOf.indices)
    assertThat(replay.missed).isEmpty()
    assertThat(replay.wrongVowels).isEmpty()
    assertThat(replay.currentWord).isEqualTo(replay.ayahOf.size)
  }

  @Test
  fun `follows al-Ikhlas to its end`() {
    val replay = replay("ikhlas.txt")
    assertThat(replay.missed).isEmpty()
    assertThat(replay.recited).containsExactlyElementsIn(replay.ayahOf.indices)
    assertThat(replay.currentWord).isEqualTo(replay.ayahOf.size)
  }

  @Test
  fun `follows al-Mulk word by word`() {
    val replay = replay("mulk.txt")
    val ayahs1To10 = (1..10).flatMap(replay::ayahWords)
    assertThat(replay.recited).containsExactlyElementsIn(ayahs1To10)
    assertThat(replay.missed).isEmpty()
    assertThat(replay.wrongVowels).isEmpty()
    assertThat(replay.expected).isInOrder()
    assertThat(replay.currentWord).isEqualTo(ayahs1To10.last() + 1)
  }

  @Test
  fun `a skipped ayah is missed and holds the reader there`() {
    val replay = replay("mulk-skip.txt") // ayahs 1, 2, then 4 to 10
    assertThat(replay.recited).containsExactlyElementsIn((1..2).flatMap(replay::ayahWords))
    // ayahs 4 to 10 were recited, but not after ayah 3
    assertThat(replay.missed).containsExactlyElementsIn(replay.ayahWords(3))
    assertThat(replay.currentWord).isEqualTo(replay.ayahWords(3).first())
  }

  @Test
  fun `a skipped ayah is recited when the reader comes back to it, then the ones after it`() {
    val replay = replay("mulk-skip-return.txt") // ayahs 1, 2, 4, 3, 4, 5
    assertThat(replay.everMissed).containsAtLeastElementsIn(replay.ayahWords(3))
    assertThat(replay.recited).containsExactlyElementsIn((1..5).flatMap(replay::ayahWords))
    assertThat(replay.missed).isEmpty()
    assertRecitedInOrder(replay, 3, 4)
  }

  /** Minshawi's al-Mulk 1-2, then Nuh 15-16 (which starts like al-Mulk 3), then al-Mulk 3-4. */
  @Test
  fun `an ayah recited from elsewhere is missed until the reader comes back to it`() {
    val replay = replay("mulk-drift.txt")
    assertThat(replay.everMissed.intersect(replay.ayahWords(3).toSet())).isNotEmpty()
    assertThat(replay.recited).containsExactlyElementsIn((1..4).flatMap(replay::ayahWords))
    assertThat(replay.missed).isEmpty()
    assertRecitedInOrder(replay, 3, 4)
  }

  /**
   * Minshawi's al-Mulk 1-7, then Ya-Sin 1-5 instead of al-Mulk 8, then al-Mulk 8-9: the reader is
   * held at al-Mulk 8 throughout, wherever in al-Mulk what they say sounds like.
   */
  @Test
  fun `an ayah skipped for another sura holds the reader there until they come back`() {
    val replay = replay("mulk-branch-off.txt")
    val ayah8 = replay.ayahWords(8)
    assertThat(replay.everMissed).containsAtLeastElementsIn(ayah8)
    val reached = replay.expected.indexOfFirst { it >= ayah8.first() }
    assertThat(replay.expected.drop(reached).minOrNull()).isEqualTo(ayah8.first())
    assertThat(replay.recited).containsExactlyElementsIn((1..9).flatMap(replay::ayahWords))
    assertThat(replay.missed).isEmpty()
    assertRecitedInOrder(replay, 8, 9)
  }

  /**
   * Minshawi's al-Kahf 1-16, then al-Ma'un (107) instead of al-Kahf 17, a long ayah: it is missed,
   * and the reader is held at its start.
   */
  @Test
  fun `a long ayah skipped for another sura is missed from its start`() {
    val replay = replay("kahf-to-maun.txt")
    val ayah17 = replay.ayahWords(17)
    assertThat(replay.recited).containsExactlyElementsIn((1..16).flatMap(replay::ayahWords))
    assertThat(replay.missed).containsExactlyElementsIn(ayah17)
    assertThat(replay.currentWord).isEqualTo(ayah17.first())
    val reached = replay.expected.indexOfFirst { it >= ayah17.first() }
    assertThat(replay.expected.drop(reached).minOrNull()).isEqualTo(ayah17.first())
  }

  /** No word of [later] was recited before the last of [earlier]. */
  private fun assertRecitedInOrder(replay: Replay, earlier: Int, later: Int) {
    val lastEarlier = replay.recitedOrder.indexOfLast { replay.ayahOf[it] == earlier }
    val firstLater = replay.recitedOrder.indexOfFirst { replay.ayahOf[it] == later }
    assertThat(firstLater).isGreaterThan(lastEarlier)
  }

  @Test
  fun `follows the reader back when they repeat an ayah`() {
    val replay = replay("mulk-repeat.txt")
    // going back to recite an earlier ayah again does not take back the word expected next
    assertThat(replay.expected).isInOrder()
    assertThat(replay.recited).containsExactlyElementsIn((1..10).flatMap(replay::ayahWords))
    assertThat(replay.missed).isEmpty()
  }

  /**
   * Minshawi's al-Mulk 1-3, edited: ayah 2 with its third word replaced by another word's audio,
   * then ayah 2 again correctly, then ayah 3 (as version 3.0 of the model heard it; the other
   * recordings are version 3.1's).
   */
  @Test
  fun `a word said wrong is missed, and recited once said again`() {
    val replay = replay("mulk-mistake.txt")
    val replaced = replay.ayahWords(2)[2] // ٱلۡمَوۡتَ
    assertThat(replay.recited).containsExactlyElementsIn((1..3).flatMap(replay::ayahWords))
    assertThat(replay.missed).isEmpty()
    assertThat(replay.expected).isInOrder()
    assertThat(replay.everMissed).contains(replaced) // the first time
    assertThat(replay.recited).contains(replaced) // the second time
  }

  /**
   * Minshawi's al-Mulk 1-2, edited: ٱلۡمُلۡكُ in ayah 1 replaced by his ٱلۡمَلِكُ (12:43), the same
   * letters with other vowels.
   */
  @Test
  fun `a word said with other vowels is recited with wrong vowels`() {
    val replay = replay("mulk-vowel.txt")
    val replaced = replay.ayahWords(1)[3] // ٱلۡمُلۡكُ
    assertThat(replay.recited).containsExactlyElementsIn((1..2).flatMap(replay::ayahWords))
    assertThat(replay.missed).isEmpty()
    assertThat(replay.wrongVowels).containsExactly(replaced)
  }
}
