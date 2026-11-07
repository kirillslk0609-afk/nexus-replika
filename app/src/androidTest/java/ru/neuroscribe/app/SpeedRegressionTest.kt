package ru.neuroscribe.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import ru.neuroscribe.app.core.Transcript
import ru.neuroscribe.app.engine.LocalLlm
import ru.neuroscribe.app.engine.ModelStore
import java.io.File

class SpeedRegressionTest {
    @Test fun optimizedPunctuationMatchesBaselineExactlyOnRealTranscriptFragments() {
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        val models=ModelStore(ctx);models.prepare({}, {})
        val inputs=JSONArray(inst.context.assets.open("punctuation-speed-texts.json").bufferedReader().use { it.readText() })
        val rows=JSONArray()
        LocalLlm(models.file("punctuation.gguf"),4).use { baseline ->
          LocalLlm(models.file("punctuation.gguf"),4).use { fast ->
            // Untimed model warm-up; baseline mode reproduces the original full generation.
            baseline.punctuate("Warm up.", optimized=false)
            fast.punctuate("Warm up.")
            for (i in 0 until inputs.length()) {
                val input=inputs.getString(i)
                var before="";var after="";var beforeNs=0L;var afterNs=0L;var calls=0
                fun run(optimized:Boolean) {
                    val llm=if(optimized) fast else baseline
                    val count=llm.generationCount;val start=System.nanoTime()
                    val candidate=llm.punctuate(input,optimized)
                    val elapsed=System.nanoTime()-start
                    val safe=Transcript.safePunctuation(input,candidate)
                    if (optimized) { after=safe;afterNs=elapsed;calls=llm.generationCount-count }
                    else { before=safe;beforeNs=elapsed }
                }
                // Alternate execution order to reduce warm-cache/order bias.
                if (i%2==0) { run(false);run(true) } else { run(true);run(false) }
                rows.put(JSONObject().put("input",input).put("baseline",before).put("optimized",after)
                    .put("baselineMs",beforeNs/1e6).put("optimizedMs",afterNs/1e6).put("optimizedNativeCalls",calls))
                File(ctx.filesDir,"speed-punctuation.json").writeText(rows.toString(2))
                assertEquals("Punctuation changed for case $i: $input", before, after)
            }
        }
        }
        val cases=(0 until rows.length()).map { rows.getJSONObject(it) }
        assertTrue("Expected repeated phrases to reuse completed results",cases.any { it.getInt("optimizedNativeCalls")==0 })
    }
}
