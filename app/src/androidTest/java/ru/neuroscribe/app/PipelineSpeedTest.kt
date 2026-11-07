package ru.neuroscribe.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import ru.neuroscribe.app.engine.ModelStore
import ru.neuroscribe.app.engine.TranscriptionEngine
import ru.neuroscribe.app.engine.EngineResult
import ru.neuroscribe.app.core.Transcript
import java.io.File

class PipelineSpeedTest {
    @Test fun pairedFullPipelinePreservesTextSpeakersAndTimes() {
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        ModelStore(ctx).prepare({}, {})
        fun fixture(name:String)=File(ctx.cacheDir,"speed-$name").also { f ->
            inst.context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        val rows=JSONArray()
        for ((index,file) in listOf(fixture("russian-noise.wav"),fixture("conversation.mp3")).withIndex()) {
            lateinit var before:EngineResult;lateinit var after:EngineResult
            fun run(fast:Boolean) {
                val result=TranscriptionEngine(ctx,fast).process(file,"ru",{_,_->},{})
                if(fast) after=result else before=result
            }
            if(index%2==0) {run(true);run(false)} else {run(false);run(true)}
            assertEquals(file.name,before.turns,after.turns)
            assertEquals(before.durationMs,after.durationMs);assertEquals(before.overlap,after.overlap)
            fun report(r:EngineResult)=JSONObject().put("processingMs",r.processingMs).put("stageMs",JSONObject(r.timingsMs))
                .put("llmCalls",r.llmCalls).put("rejectedEdits",r.rejectedEdits).put("result",JSONObject(Transcript.toJson(r.turns)))
            rows.put(JSONObject().put("fixture",file.name).put("durationMs",before.durationMs).put("before",report(before)).put("after",report(after)))
            File(ctx.filesDir,"speed-pipeline.json").writeText(rows.toString(2))
        }
    }
}
