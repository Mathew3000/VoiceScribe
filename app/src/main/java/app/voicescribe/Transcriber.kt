package app.voicescribe

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.abs
import kotlin.math.min

class Transcriber(private val store: ModelStore, language: String = "de") {
    private val rate = 16_000

    private val recognizer = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = store.encoder,
                    decoder = store.decoder,
                    language = language,
                    task = "transcribe",
                ),
                tokens = store.tokens,
                numThreads = 4,
            )
        )
    )

    /** onProgress(completedChunks, totalChunks) */
    fun transcribe(raw: FloatArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): String {
        val samples = normalize(raw)
        val chunks = speechChunks(samples)
        val sb = StringBuilder()
        onProgress(0, chunks.size)
        chunks.forEachIndexed { idx, (from, to) ->
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples.copyOfRange(from, to), rate)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            stream.release()
            if (text.isNotEmpty()) { if (sb.isNotEmpty()) sb.append(' '); sb.append(text) }
            onProgress(idx + 1, chunks.size)
        }
        return collapseRepeats(sb.toString())
    }

    /** Peak-normalise quiet recordings (gain capped so noise is not blown up). */
    private fun normalize(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) peak = maxOf(peak, abs(v))
        if (peak < 1e-4f) return x
        val gain = min(0.9f / peak, 12f)
        if (gain <= 1.05f) return x
        return FloatArray(x.size) { x[it] * gain }
    }

    /**
     * Cuts at real speech pauses (Silero VAD), then merges neighbouring speech
     * segments into chunks of at most ~20 s so Whisper keeps enough context
     * without drifting. Falls back to energy-based splitting.
     */
    private fun speechChunks(x: FloatArray): List<Pair<Int, Int>> {
        val vad = Vad(
            null,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = store.vad,
                    threshold = 0.45f,
                    minSilenceDuration = 0.45f,
                    minSpeechDuration = 0.2f,
                    windowSize = 512,
                    maxSpeechDuration = 15f,
                ),
                sampleRate = rate,
                numThreads = 1,
            )
        )
        val segs = ArrayList<Pair<Int, Int>>()
        fun drain() {
            while (!vad.empty()) {
                val s = vad.front()
                segs += s.start.toInt() to (s.start.toInt() + s.samples.size)
                vad.pop()
            }
        }
        try {
            val win = 512
            var p = 0
            while (p + win <= x.size) {
                vad.acceptWaveform(x.copyOfRange(p, p + win))
                drain()
                p += win
            }
            vad.flush()
            drain()
        } finally { vad.release() }

        if (segs.isEmpty()) return fallbackSplit(x)

        val pad = (0.25f * rate).toInt()
        val maxLen = 20 * rate
        val out = ArrayList<Pair<Int, Int>>()
        var start = maxOf(0, segs[0].first - pad)
        var end = min(x.size, segs[0].second + pad)
        for (i in 1 until segs.size) {
            val s = maxOf(0, segs[i].first - pad)
            val e = min(x.size, segs[i].second + pad)
            if (e - start <= maxLen) end = maxOf(end, e)
            else { out += start to end; start = maxOf(s, end); end = e }
        }
        out += start to end
        return out
    }

    private fun fallbackSplit(x: FloatArray): List<Pair<Int, Int>> {
        val max = 28 * rate
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (x.size - start > max) {
            val win = rate / 10
            var best = start + max
            var bestE = Float.MAX_VALUE
            var p = start + max - 4 * rate
            while (p + win <= start + max) {
                var e = 0f
                for (i in p until p + win step 8) e += abs(x[i])
                if (e < bestE) { bestE = e; best = p + win / 2 }
                p += win / 2
            }
            out += start to best
            start = best
        }
        out += start to x.size
        return out
    }

    /** Whisper sometimes loops ("und dann und dann und dann ..."): keep a phrase at most twice. */
    private fun collapseRepeats(text: String): String {
        var words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (n in 6 downTo 1) {
            val out = ArrayList<String>()
            var i = 0
            while (i < words.size) {
                if (i + n <= words.size) {
                    var reps = 1
                    while (i + (reps + 1) * n <= words.size &&
                        (0 until n).all { k -> same(words[i + k], words[i + reps * n + k]) }) reps++
                    if (reps >= 3) {
                        repeat(2) { r -> for (k in 0 until n) out += words[i + r * n + k] }
                        i += reps * n
                        continue
                    }
                }
                out += words[i]; i++
            }
            words = out
        }
        return words.joinToString(" ")
    }

    private fun same(a: String, b: String) =
        a.trim { !it.isLetterOrDigit() }.equals(b.trim { !it.isLetterOrDigit() }, ignoreCase = true)

    fun release() = recognizer.release()
}
