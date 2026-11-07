package ru.neuroscribe.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
import ru.neuroscribe.app.audio.AudioDecoder
import ru.neuroscribe.app.engine.ModelStore
import ru.neuroscribe.app.engine.StableDiarizer
import java.io.File

class DiarizationRegressionTest {
    @Test fun differentHeadcountsAndUnseenConversationsKeepAutomaticSpeakerIds() {
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        val store=ModelStore(ctx);store.prepare({}, {})
        val diarizer=StableDiarizer(store,4);val reports=JSONArray()
        val cases=listOf("silence.wav" to 0,"russian.wav" to 1,"russian-noise.wav" to 1,"russian-pauses.wav" to 1,
            "two-speakers.wav" to 2,"synthetic-two.wav" to 2,"three-speakers.wav" to 3,"four-speakers.wav" to 4,
            "conversation.mp3" to 2,"conversation-second.mp3" to 2,"development-ru.mp3" to 2,"development-en.mp3" to 2,
            "validation-ru.mp3" to 2,"validation-en.mp3" to 2,"unseen-ru.mp3" to 2,"unseen-en.mp3" to 2)
        val baseline=JSONArray(inst.context.assets.open("diarization-v02-baseline.json").bufferedReader().use { it.readText() })
        val failures=mutableListOf<String>()
        for ((name,expected) in cases) {
            val file=File(ctx.cacheDir,"regression-$name")
            inst.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            val start=System.nanoTime();val pcm=AudioDecoder.decode(file) {};val regions=diarizer.process(pcm)
            if (name=="four-speakers.wav") {
                fun speakerAt(time:Double)=regions.single { it.start<=time && it.end>time }.speaker
                assertEquals(speakerAt(3.59),speakerAt(23.49))
                assertEquals(speakerAt(3.59),speakerAt(53.57))
                assertEquals(speakerAt(15.39),speakerAt(49.25))
                assertEquals(speakerAt(28.56),speakerAt(35.81))
                assertEquals(4,listOf(3.59,8.88,15.39,28.56).map { speakerAt(it) }.distinct().size)
            }
            // Changing ASR must preserve every speaker assignment and 20 ms boundary, not only the headcount.
            val previous=(0 until baseline.length()).map { baseline.getJSONObject(it) }.single { it.getString("fixture")==name }.getJSONArray("regions")
            assertEquals("$name region count",previous.length(),regions.size)
            regions.forEachIndexed { i,r ->
                val old=previous.getJSONObject(i)
                assertEquals("$name region $i speaker",old.getInt("speaker"),r.speaker)
                assertEquals("$name region $i start",old.getDouble("start"),r.start,0.000001)
                assertEquals("$name region $i end",old.getDouble("end"),r.end,0.000001)
            }
            val actual=regions.map { it.speaker }.distinct().size
            val turns=JSONArray();regions.forEach { r ->
                assertTrue(name,r.start>=0 && r.end>r.start && r.end<=pcm.size/16000.0)
                turns.put(JSONObject().put("speaker",r.speaker).put("start",r.start).put("end",r.end))
            }
            reports.put(JSONObject().put("fixture",name).put("expectedSpeakers",expected).put("detectedSpeakers",actual)
                .put("durationSeconds",pcm.size/16000.0).put("diarizationAndDecodeMs",(System.nanoTime()-start)/1_000_000).put("regions",turns))
            File(ctx.filesDir,"diarization-regression.json").writeText(reports.toString(2))
            if (actual!=expected) failures.add("$name: expected $expected, got $actual")
        }
        assertTrue(failures.joinToString("; "),failures.isEmpty())
    }
}
