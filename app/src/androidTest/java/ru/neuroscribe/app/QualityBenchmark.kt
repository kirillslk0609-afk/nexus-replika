package ru.neuroscribe.app

import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
import ru.neuroscribe.app.audio.AudioDecoder
import ru.neuroscribe.app.engine.ModelStore
import ru.neuroscribe.app.engine.TranscriptionEngine
import ru.neuroscribe.app.core.Transcript
import java.io.File

/** Writes measurements; unlabelled conversations are not claimed to have measured WER/DER. */
class QualityBenchmark {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private fun fixture(name:String)=File(ctx.cacheDir,"benchmark-$name").also { f ->
        inst.context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
    }
    @Test fun knownSpeakerCountsAcrossDifferentRecordings() {
        val store=ModelStore(ctx);store.prepare({}, {})
        val diarizer=ru.neuroscribe.app.engine.StableDiarizer(store,4)
        for ((name,expected) in listOf("russian.wav" to 1,"two-speakers.wav" to 2,"synthetic-two.wav" to 2,"four-speakers.wav" to 4)) {
            val regions=diarizer.process(AudioDecoder.decode(fixture(name)) {})
            assertEquals(name,expected,regions.map { it.speaker }.distinct().size)
        }
    }
    @Test fun noisyRussianAndLongPauseKeepValidOutput() {
        for (name in listOf("russian-noise.wav","russian-pauses.wav")) {
            val r=TranscriptionEngine(ctx).process(fixture(name),"ru",{_,_->},{})
            assertTrue(name,r.turns.isNotEmpty())
            assertEquals(name,1,r.turns.map { it.speakerId }.distinct().size)
            if (name.contains("pauses")) assertTrue(r.turns.any { it.startMs>15000 })
            save(name,r)
        }
    }
    @Test fun measureNaturalRussianConversations() {
        for (name in listOf("conversation.mp3","conversation-second.mp3")) {
            val r=TranscriptionEngine(ctx).process(fixture(name),"ru",{_,_->},{})
            assertTrue(name,r.turns.isNotEmpty())
            assertTrue(r.llmCalls>0)
            assertEquals(name,2,r.turns.map { it.speakerId }.distinct().size)
            save(name,r)
        }
    }
    private fun save(name:String,r:ru.neuroscribe.app.engine.EngineResult) {
        val baseline=JSONObject(inst.context.assets.open("transcripts-v03-baseline.json").bufferedReader().use { it.readText() })
        assertEquals("$name final transcript", Transcript.fromJson(baseline.getJSONObject("quality-$name").toString()), r.turns)
        r.turns.forEach { assertTrue(it.startMs>=0 && it.endMs>it.startMs && it.endMs<=r.durationMs) }
        File(ctx.filesDir,"quality-$name.json").writeText(JSONObject()
            .put("stageMs",JSONObject(r.timingsMs)).put("fixture",name).put("result",JSONObject(Transcript.toJson(r.turns)))
            .put("durationMs",r.durationMs).put("processingMs",r.processingMs)
            .put("llmCalls",r.llmCalls).put("rejectedEdits",r.rejectedEdits).put("overlap",r.overlap)
            .toString(2))
    }
}
