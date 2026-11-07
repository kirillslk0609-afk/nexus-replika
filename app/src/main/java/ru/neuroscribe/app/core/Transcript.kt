package ru.neuroscribe.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

data class Turn(val speakerId: String, val startMs: Long, val endMs: Long, val text: String) {
    init {
        require(startMs >= 0 && endMs > startMs)
        require(speakerId.isNotBlank() && text.isNotBlank())
    }
}

data class SpeakerRegion(val speaker: Int, val start: Double, val end: Double)
data class AudioSlice(val speakerId: String, val startSample: Int, val endSample: Int) {
    val startMs get() = startSample * 1000L / 16000
    val endMs get() = endSample * 1000L / 16000
}

object Transcript {
    /** IDs are stable by first occurrence, not the arbitrary cluster numbering. */
    fun slices(regions: List<SpeakerRegion>, sampleCount: Int, maxSeconds: Int = 25): List<AudioSlice> {
        require(sampleCount >= 0 && maxSeconds > 0)
        val ids = linkedMapOf<Int, String>()
        val merged = mutableListOf<AudioSlice>()
        regions.filter { it.start.isFinite() && it.end.isFinite() && it.end > it.start }
            .sortedBy { it.start }.forEach { region ->
                val start = (region.start * 16000).roundToLong().coerceIn(0, sampleCount.toLong()).toInt()
                val end = (region.end * 16000).roundToLong().coerceIn(0, sampleCount.toLong()).toInt()
                if (end <= start) return@forEach
                val id = ids.getOrPut(region.speaker) { "speaker_${ids.size + 1}" }
                val last = merged.lastOrNull()
                if (last != null && last.speakerId == id && start <= last.endSample + 3200 &&
                    end - last.startSample <= maxSeconds * 16000) {
                    merged[merged.lastIndex] = last.copy(endSample = maxOf(last.endSample, end))
                } else merged.add(AudioSlice(id, start, end))
            }
        return merged.flatMap { region ->
            buildList {
                var start = region.startSample
                while (start < region.endSample) {
                    val end = minOf(start + maxSeconds * 16000, region.endSample)
                    add(region.copy(startSample = start, endSample = end))
                    start = end
                }
            }
        }
    }

    fun toJson(turns: List<Turn>): String = JSONObject().put("transcript", JSONArray().apply {
        turns.sortedBy { it.startMs }.forEach { turn ->
            put(JSONObject().put("speaker_id", turn.speakerId).put("start_ms", turn.startMs)
                .put("end_ms", turn.endMs).put("text", turn.text))
        }
    }).toString(2)

    fun fromJson(json: String): List<Turn> {
        val items = JSONObject(json).getJSONArray("transcript")
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            Turn(o.getString("speaker_id"), o.getLong("start_ms"), o.getLong("end_ms"), o.getString("text"))
        }
    }

    /** A language model may change punctuation/case only; no inserted/deleted/reordered words. */
    fun safePunctuation(original: String, candidate: String): String {
        fun words(s: String) = Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase(Locale.ROOT)).map { it.value }.toList()
        fun numbers(s: String) = Regex("[+\\-−]?\\p{N}+(?:[.,:/\\-]\\p{N}+)*(?:\\s*%)?")
            .findAll(s).map { it.value.replace(Regex("\\s+"), "") }.toList()
        val clean = candidate.trim()
        return if (clean.isNotEmpty() && clean.length <= original.length * 2 + 32 &&
            words(clean) == words(original) && numbers(clean) == numbers(original)) clean else original
    }

    fun clock(ms: Long): String = "%02d:%02d.%03d".format(Locale.ROOT, ms / 60000, ms / 1000 % 60, ms % 1000)
}
