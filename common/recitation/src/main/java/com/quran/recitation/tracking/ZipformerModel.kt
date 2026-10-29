package com.quran.recitation.tracking

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Quran-Lab's zipformer_p-arabic-v3 (https://huggingface.co/Quran-Lab/zipformer_p-arabic-v3), in
 * its version 3.1, a streaming phoneme recognizer for Quran recitation, under the Quran-Lab
 * No-Profit License 1.2: downloaded on first use; its phoneme units ship in the app's assets.
 */
object ZipformerModel {
  private const val NAME = "zipformer_p_arabic_v3.1.int8.onnx"
  // A copy of the Hugging Face file, with its license, on this fork's releases.
  private val URLS = listOf(
    "https://github.com/quran-recitation/quran_android/releases/download/zipformer-v3.1/$NAME",
  )
  /** The Hugging Face file's: a download that differs is not used. */
  private const val SHA256 = "31755836528da336a6192121cd7bc82cb41752dddb65566fd000b89c8686da6b"
  private const val MIN_SIZE = 10L * 1024 * 1024

  /** The download's size, in (rounded) megabytes. */
  const val DOWNLOAD_MEGABYTES = 73

  private fun directory(context: Context) = File(context.filesDir, "recitation")

  fun file(context: Context): File = File(directory(context), NAME)

  fun isDownloaded(context: Context): Boolean = file(context).let { it.exists() && it.length() > MIN_SIZE }

  /** The model's phoneme units, copied out of the app's assets for the recognizer. */
  suspend fun tokens(context: Context): File = withContext(Dispatchers.IO) {
    val file = File(directory(context), "zipformer-tokens.txt")
    if (!file.exists()) {
      file.parentFile?.mkdirs()
      val partial = File(file.path + ".part")
      context.assets.open("recitation/tokens.txt").use { input -> partial.outputStream().use { input.copyTo(it) } }
      check(partial.renameTo(file)) { "Could not save the model's tokens" }
    }
    file
  }

  /**
   * Downloads the model. [onProgress] gets the percentage done (or -1 when the size is unknown)
   * and the bytes so far, on the main thread, each time the percentage changes.
   */
  suspend fun download(context: Context, onProgress: (percent: Long, bytes: Long) -> Unit): Unit =
    withContext(Dispatchers.IO) {
      val modelFile = file(context)
      val partial = File(modelFile.path + ".part")
      partial.parentFile?.mkdirs()
      val client = OkHttpClient()
      var failure: Exception? = null
      for (url in URLS) {
        try {
          val digest = MessageDigest.getInstance("SHA-256")
          client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body
            val total = body.contentLength()
            body.byteStream().use { input ->
              partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var lastShown = -2L
                while (true) {
                  ensureActive()
                  val read = input.read(buffer)
                  if (read < 0) break
                  output.write(buffer, 0, read)
                  digest.update(buffer, 0, read)
                  done += read
                  val percent = if (total > 0) done * 100 / total else -1
                  if (percent != lastShown || total <= 0) {
                    lastShown = percent
                    withContext(Dispatchers.Main) { onProgress(percent, done) }
                  }
                }
              }
            }
          }
          check(partial.length() > MIN_SIZE) { "incomplete download" }
          val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
          check(sha256 == SHA256) { "the download is not the model" }
          check(partial.renameTo(modelFile)) { "Could not save the model" }
          // Earlier versions of the model are not used any more.
          directory(context).listFiles { f -> f.name.endsWith(".onnx") && f != modelFile }
            ?.forEach { it.delete() }
          return@withContext
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          failure = e
        }
      }
      partial.delete()
      throw failure ?: IllegalStateException("no download address")
    }
}
