package ru.neuroscribe.app.engine

import android.content.Context
import ru.neuroscribe.app.audio.AudioDecoder
import ru.neuroscribe.app.core.*
import java.io.File
import kotlin.math.min

data class EngineResult(val turns: List<Turn>, val durationMs: Long, val processingMs: Long, val llmCalls: Int, val rejectedEdits: Int, val overlap: Boolean, val timingsMs: Map<String, Long> = emptyMap())

class TranscriptionEngine(private val context: Context, private val optimized: Boolean = true) {
    fun process(file: File, language: String, progress: (String, Int) -> Unit, checkCancelled: () -> Unit): EngineResult {
        val started = System.nanoTime()
        val timings = linkedMapOf<String, Long>()
        var boundary = started
        fun mark(stage: String) {
            val now = System.nanoTime()
            timings[stage] = (now - boundary) / 1_000_000
            boundary = now
        }
        // Include first-run extraction and integrity checks in user-visible RTF.
        val models = ModelStore(context)
        models.prepare({ progress(it, 0) }, checkCancelled)
        mark("models")
        progress("Читаем аудиозапись", 3)
        val samples = AudioDecoder.decode(file, optimized, checkCancelled)
        require(samples.size >= 1600) { "Запись слишком короткая: меньше 0,1 секунды." }
        val threads = min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
        mark("decode")
        progress("Определяем говорящих", 8)
        val regions = StableDiarizer(models, threads).process(samples, progress, checkCancelled)
        checkCancelled()
        mark("diarization")
        val slices = Transcript.slices(regions, samples.size)
        if (slices.isEmpty()) return EngineResult(emptyList(), samples.size * 1000L / 16000, (System.nanoTime() - started) / 1_000_000, 0, 0, false, timings.toMap())
        val sorted = regions.sortedBy { it.start }
        val overlap = sorted.indices.any { i -> (0 until i).any { j -> sorted[j].speaker != sorted[i].speaker && sorted[j].end > sorted[i].start } }
        progress("Загружаем распознавание речи", 34)
        val recognizer = SpeechRecognizer(models, language, threads)
        mark("asrLoad")
        val turns = mutableListOf<Turn>()
        try {
            slices.forEachIndexed { index, slice ->
                checkCancelled()
                progress("Распознаём реплику ${index + 1}/${slices.size}", 35 + 45 * index / slices.size)
                // Audio outside the detected turn is not included, preventing a neighbouring speaker's words from leaking in.
                val fragment = samples.copyOfRange(slice.startSample, slice.endSample)
                val text = recognizer.recognize(fragment, checkCancelled)
                if (text.isNotBlank()) turns.add(Turn(slice.speakerId, slice.startMs, slice.endMs, text))
            }
        } finally { recognizer.close() }
        checkCancelled()
        mark("asr")
        var calls = 0; var rejected = 0
        if (turns.isNotEmpty()) {
            progress("Загружаем локальную LLM", 81)
            LocalLlm(models.file("punctuation.gguf"), threads).use { llm ->
                mark("llmLoad")
                turns.indices.forEach { i ->
                    checkCancelled()
                    progress("Восстанавливаем пунктуацию ${i + 1}/${turns.size}", 82 + 17 * i / turns.size)
                    val raw = turns[i].text
                    val candidate = llm.punctuate(raw, optimized = optimized)
                    val safe = Transcript.safePunctuation(raw, candidate)
                    if (safe != candidate.trim()) rejected++
                    turns[i] = turns[i].copy(text = safe)
                    calls = llm.generationCount
                }
            }
        }
        checkCancelled()
        mark("llm")
        return EngineResult(turns, samples.size * 1000L / 16000, (System.nanoTime() - started) / 1_000_000, calls, rejected, overlap, timings.toMap())
    }
}
