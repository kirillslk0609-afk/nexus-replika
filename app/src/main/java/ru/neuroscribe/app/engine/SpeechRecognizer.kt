package ru.neuroscribe.app.engine

import com.k2fsa.sherpa.onnx.*
import ru.neuroscribe.app.core.AsrRepetition
import java.io.Closeable

/** One model session per recording. No reference text or network is used. */
class SpeechRecognizer(models: ModelStore, language: String, threads: Int) : Closeable {
    private val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
        whisper = OfflineWhisperModelConfig(encoder = models.file("encoder.onnx"), decoder = models.file("decoder.onnx"), language = language, task = "transcribe"),
        tokens = models.file("tokens.txt"), numThreads = threads, modelType = "whisper"
    )))
    var retryCount: Int = 0
        private set

    fun recognize(samples: FloatArray, checkCancelled: () -> Unit, retryLoops: Boolean = true): String {
        fun decode(audio: FloatArray): String {
            checkCancelled()
            val stream = recognizer.createStream()
            return try {
                stream.acceptWaveform(audio, 16000)
                recognizer.decode(stream)
                checkCancelled()
                recognizer.getResult(stream).text.trim()
            } finally { stream.release() }
        }
        val original = decode(samples)
        if (!retryLoops || !AsrRepetition.isLoop(original)) return original
        // 200 ms of silence changes Whisper's feature alignment without borrowing a neighbour's speech.
        // The diarization input, sample array, speaker IDs and exported timestamps are untouched.
        val padded = FloatArray(samples.size + 6400)
        samples.copyInto(padded, 3200)
        retryCount++
        return AsrRepetition.choose(original, decode(padded))
    }

    override fun close() = recognizer.release()
}
