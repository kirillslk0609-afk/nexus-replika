package ru.neuroscribe.app.core

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class TranscriptTest {
    @Test fun stableIdsAndMillisecondsAreDerivedFromAudio() {
        val result = Transcript.slices(listOf(SpeakerRegion(9, 1.25, 4.38), SpeakerRegion(3, 5.0, 6.0), SpeakerRegion(9, 7.0, 8.0)), 160000)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), result.map { it.speakerId })
        assertEquals(1250L, result.first().startMs)
        assertEquals(4380L, result.first().endMs)
    }
    @Test fun chunksPreserveAbsoluteTimesAndDoNotLoseTail() {
        val result = Transcript.slices(listOf(SpeakerRegion(0, 2.0, 63.0)), 70 * 16000)
        assertEquals(listOf(2000L, 27000L, 52000L), result.map { it.startMs })
        assertEquals(63000L, result.last().endMs)
        result.zipWithNext().forEach { (a, b) -> assertEquals(a.endSample, b.startSample) }
    }
    @Test fun clampsModelBoundariesAndRejectsInvalidRegions() {
        val result = Transcript.slices(listOf(SpeakerRegion(0, -2.0, 2.0), SpeakerRegion(1, Double.NaN, 4.0), SpeakerRegion(0, 4.0, 3.0)), 16000)
        assertEquals(1, result.size)
        assertEquals(0L, result[0].startMs)
        assertEquals(1000L, result[0].endMs)
    }
    @Test fun speakerOverlapIsNotSilentlyFlattened() {
        val result = Transcript.slices(listOf(SpeakerRegion(0, 0.0, 3.0), SpeakerRegion(1, 2.0, 4.0)), 64000)
        assertEquals(2, result.size)
        assertTrue(result[0].endMs > result[1].startMs)
    }
    @Test fun punctuationCannotChangeWordsNumbersOrOrder() {
        val raw = "давайте обсудим бюджет 1500 рублей"
        assertEquals("Давайте обсудим бюджет: 1500 рублей.", Transcript.safePunctuation(raw, "Давайте обсудим бюджет: 1500 рублей."))
        for (bad in listOf("Давайте обсудим бюджет 500 рублей.", "Давайте обсудим большой бюджет 1500 рублей.", "Бюджет 1500 рублей давайте обсудим.", "", "Translation: let's discuss the budget")) {
            assertEquals(raw, Transcript.safePunctuation(raw, bad))
        }
    }
    @Test fun jsonHasExactContractAndEscapesUnicodeQuotesAndNewlines() {
        val turns = listOf(Turn("speaker_1", 1250, 4380, "Он сказал: \"Привет\".\nДа."))
        val json = Transcript.toJson(turns)
        val root = JSONObject(json)
        assertEquals(setOf("transcript"), root.keys().asSequence().toSet())
        val item = root.getJSONArray("transcript").getJSONObject(0)
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), item.keys().asSequence().toSet())
        assertTrue(item.get("start_ms") is Number)
        assertEquals(1250L, item.getLong("start_ms"))
        assertEquals(turns, Transcript.fromJson(json))
        assertEquals(0, JSONObject(Transcript.toJson(emptyList())).getJSONArray("transcript").length())
    }
    @Test fun punctuationCannotRewriteNumericSignsFractionsOrPercentages() {
        for ((raw,bad) in listOf("баланс -100 рублей" to "Баланс 100 рублей.",
            "рост 5%" to "Рост 5.", "доля 1/2" to "Доля 1-2.", "время 12:30" to "Время 12.30.")) {
            assertEquals(raw,Transcript.safePunctuation(raw,bad))
        }
        assertEquals("Баланс: -100 рублей.",Transcript.safePunctuation("баланс -100 рублей","Баланс: -100 рублей."))
    }
}
