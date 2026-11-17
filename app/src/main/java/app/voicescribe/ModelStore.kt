package app.voicescribe

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whisper model (small or medium, multilingual, int8, sherpa-onnx format) plus the shared
 * Silero VAD model. Downloaded on first use, resumable.
 */
class ModelStore(context: Context, val variant: String) {
    private class Item(val name: String, val url: String, val estimate: Long, val dir: File)

    private val modelDir = File(context.filesDir, "whisper-$variant").apply { mkdirs() }
    private val sharedDir = File(context.filesDir, "vad").apply { mkdirs() }
    private val hf = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$variant/resolve/main/"

    init {
        // earlier builds kept the VAD model next to the small model
        val old = File(context.filesDir, "whisper-small/silero_vad.onnx")
        val new = File(sharedDir, "silero_vad.onnx")
        if (old.exists() && !new.exists()) old.renameTo(new)
    }

    private val sizes = mapOf(
        "small" to longArrayOf(112_000_000L, 262_000_000L),
        "medium" to longArrayOf(374_000_000L, 571_000_000L),
    ).getValue(variant)

    private val items = listOf(
        Item("$variant-encoder.int8.onnx", hf + "$variant-encoder.int8.onnx", sizes[0], modelDir),
        Item("$variant-decoder.int8.onnx", hf + "$variant-decoder.int8.onnx", sizes[1], modelDir),
        Item("$variant-tokens.txt", hf + "$variant-tokens.txt", 817_000L, modelDir),
        Item(
            "silero_vad.onnx",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            644_000L, sharedDir,
        ),
    )

    val encoder: String get() = File(modelDir, "$variant-encoder.int8.onnx").absolutePath
    val decoder: String get() = File(modelDir, "$variant-decoder.int8.onnx").absolutePath
    val tokens: String get() = File(modelDir, "$variant-tokens.txt").absolutePath
    val vad: String get() = File(sharedDir, "silero_vad.onnx").absolutePath

    private fun have(i: Item) = File(i.dir, i.name).length() > 0
    fun isReady() = items.all { have(it) }

    /** Approximate number of bytes still to download. */
    fun pendingBytes(): Long = items.filter { !have(it) }.sumOf { it.estimate }

    private fun size(item: Item): Long = try {
        val c = URL(item.url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.connectTimeout = 15_000
        val n = c.contentLengthLong; c.disconnect()
        if (n > 0) n else item.estimate
    } catch (e: Exception) { item.estimate }

    /** Resumable download. onProgress(currentFile, bytesDone, bytesTotal) covers all files together. */
    fun download(onProgress: (String, Long, Long) -> Unit) {
        val pending = items.filter { !have(it) }
        val sizes = pending.associateWith { size(it) }
        val total = sizes.values.sum()
        var done = 0L
        for (item in pending) {
            val target = File(item.dir, item.name)
            val tmp = File(item.dir, "${item.name}.part")
            var got = if (tmp.exists()) tmp.length() else 0L
            val conn = URL(item.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            if (got > 0) conn.setRequestProperty("Range", "bytes=$got-")
            val resumed = conn.responseCode == 206
            if (!resumed) got = 0L
            RandomAccessFile(tmp, "rw").use { out ->
                if (resumed) out.seek(got) else out.setLength(0)
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val now = System.currentTimeMillis()
                        if (now - last > 200) { last = now; onProgress(item.name, done + got, total) }
                    }
                }
            }
            conn.disconnect()
            tmp.renameTo(target)
            done += sizes.getValue(item)
            onProgress(item.name, done, total)
        }
    }
}
