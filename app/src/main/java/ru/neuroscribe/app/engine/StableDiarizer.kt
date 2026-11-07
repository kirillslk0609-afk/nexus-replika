package ru.neuroscribe.app.engine

import com.k2fsa.sherpa.onnx.*
import ru.neuroscribe.app.core.SpeakerClustering
import ru.neuroscribe.app.core.SpeakerRegion
import kotlin.math.*

/** Local segmentation -> clean voice tracks -> recording-wide clustering -> overlapping-window voting. */
class StableDiarizer(private val models: ModelStore, private val threads: Int) {
    private data class Window(val start: Int, val end: Int)
    private data class Track(val window: Int, val intervals: List<Pair<Int, Int>>, var vector: FloatArray? = null, var cleanSeconds: Double = 0.0)

    fun process(samples: FloatArray, progress: (String, Int) -> Unit = { _, _ -> }, checkCancelled: () -> Unit = {}, onClustering: ((List<FloatArray>, DoubleArray, IntArray) -> Unit)? = null): List<SpeakerRegion> {
        if (samples.isEmpty()) return emptyList()
        val windows = mutableListOf<Window>()
        val tracks = mutableListOf<Track>()
        val segmenter = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = models.file("segmentation.onnx")), numThreads = threads),
            embedding = SpeakerEmbeddingExtractorConfig(model = models.file("embedding.onnx"), numThreads = threads),
            clustering = FastClusteringConfig(numClusters = -1, threshold = 0.7f),
            minDurationOn = 0.15f, minDurationOff = 0.3f))
        try {
            require(segmenter.sampleRate() == 16000)
            var start = 0
            while (start < samples.size) {
                checkCancelled()
                val end = min(start + 160000, samples.size)
                val window = windows.size; windows.add(Window(start, end))
                // <= 10 seconds takes sherpa's local segmentation path, without global short-track clustering.
                val regions = segmenter.process(samples.copyOfRange(start, end))
                regions.groupBy { it.speaker }.values.forEach { local ->
                    val intervals = local.map { r ->
                        (start + (r.start * 16000).roundToInt()).coerceIn(start, end) to
                            (start + (r.end * 16000).roundToInt()).coerceIn(start, end)
                    }.filter { it.second > it.first }
                    if (intervals.isNotEmpty()) tracks.add(Track(window, intervals))
                }
                progress("Находим границы речи", 8 + 8 * end / samples.size)
                if (end == samples.size) break
                start += 80000
            }
        } finally { segmenter.release() }
        if (tracks.isEmpty()) return emptyList()
        val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = models.file("embedding.onnx"), numThreads = threads))
        try {
            tracks.forEachIndexed { i, track ->
                checkCancelled()
                var clean = track.intervals
                tracks.filter { it !== track && it.window == track.window }.flatMap { it.intervals }.forEach { (c, d) ->
                    clean = clean.flatMap { (a, b) ->
                        if (c >= b || d <= a) listOf(a to b)
                        else buildList { if (c > a) add(a to c); if (d < b) add(d to b) }
                    }
                }
                val length = clean.sumOf { it.second - it.first }
                track.cleanSeconds = length / 16000.0
                if (length >= 8000) {
                    val clip = FloatArray(length); var offset = 0
                    for ((a, b) in clean) { samples.copyInto(clip, offset, a, b); offset += b - a }
                    val stream = extractor.createStream()
                    track.vector = try {
                        stream.acceptWaveform(clip, 16000); stream.inputFinished()
                        if (extractor.isReady(stream)) SpeakerClustering.normalize(extractor.compute(stream)) else null
                    } finally { stream.release() }
                }
                progress("Сравниваем голоса", 16 + 10 * (i + 1) / tracks.size)
            }
        } finally { extractor.release() }
        checkCancelled()
        val available = tracks.indices.filter { tracks[it].vector != null }
        val anchors = available.filter { tracks[it].cleanSeconds >= 1.5 }.ifEmpty { available }
        val labels = IntArray(tracks.size) { -1 }
        var count = 1
        if (anchors.isNotEmpty()) {
            val vectors = anchors.map { tracks[it].vector!! }
            val anchorLabels = SpeakerClustering.cluster(vectors, DoubleArray(anchors.size) { tracks[anchors[it]].cleanSeconds }, checkCancelled)
            onClustering?.invoke(vectors, DoubleArray(anchors.size) { tracks[anchors[it]].cleanSeconds }, anchorLabels)
            val centers = SpeakerClustering.centers(vectors, anchorLabels); count = centers.size
            available.forEach { labels[it] = SpeakerClustering.nearest(tracks[it].vector!!, centers) }
            anchors.forEachIndexed { i, track -> labels[track] = anchorLabels[i] }
        }
        // Tracks without enough clean audio borrow an ID from overlapping evidence in another window.
        val reliable = tracks.indices.filter { labels[it] >= 0 }
        tracks.indices.filter { labels[it] < 0 }.forEach { i ->
            val scores = DoubleArray(count); var nearest = 0; var nearestDistance = Int.MAX_VALUE
            for (j in reliable) if (tracks[j].window != tracks[i].window) {
                for ((a, b) in tracks[i].intervals) for ((c, d) in tracks[j].intervals) {
                    scores[labels[j]] += max(0, min(b, d) - max(a, c)).toDouble()
                    val distance = max(0, max(c - b, a - d))
                    if (distance < nearestDistance) { nearestDistance = distance; nearest = labels[j] }
                }
            }
            labels[i] = if (scores.maxOrNull()!! > 0) scores.indices.maxBy { scores[it] } else nearest
        }
        progress("Объединяем реплики", 30)
        val frames = (samples.size + 319) / 320
        val votes = Array(count) { FloatArray(frames) }
        val coverage = FloatArray(frames)
        val speakerCount = FloatArray(frames)
        windows.forEachIndexed { wi, window ->
            checkCancelled()
            val startFrame = window.start / 320; val endFrame = (window.end + 319) / 320
            val local = Array(count) { BooleanArray(endFrame - startFrame) }
            tracks.indices.filter { tracks[it].window == wi }.forEach { ti ->
                for ((a, b) in tracks[ti].intervals) {
                    val lo = (a / 320.0).roundToInt().coerceIn(startFrame, endFrame)
                    val hi = (b / 320.0).roundToInt().coerceIn(lo, endFrame)
                    for (f in lo until hi) local[labels[ti]][f - startFrame] = true
                }
            }
            for (f in startFrame until endFrame) {
                val middle = f * 320 + 160
                val weight = min((middle - window.start) / 40000f, (window.end - middle) / 40000f).coerceIn(0.1f, 1f)
                coverage[f] += weight
                for (k in 0 until count) if (local[k][f - startFrame]) { votes[k][f] += weight; speakerCount[f] += weight }
            }
        }
        val active = Array(count) { BooleanArray(frames) }
        for (f in 0 until frames) {
            if (f % 1000 == 0) checkCancelled()
            val n = (speakerCount[f] / max(coverage[f], 1e-9f)).roundToInt().coerceIn(0, count)
            if (n > 0) (0 until count).sortedByDescending { votes[it][f] }.take(n).forEach { active[it][f] = true }
        }
        val result = mutableListOf<SpeakerRegion>()
        for (k in 0 until count) {
            var start = -1
            for (f in 0..frames) {
                val on = f < frames && active[k][f]
                if (on && start < 0) start = f
                if (!on && start >= 0) {
                    val endSample = min(samples.size, f * 320)
                    if (endSample - start * 320 >= 2400) result.add(SpeakerRegion(k, start * 0.02, endSample / 16000.0))
                    start = -1
                }
            }
        }
        return result.sortedBy { it.start }
    }
}
