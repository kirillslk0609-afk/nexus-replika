package ru.neuroscribe.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import android.net.Uri
import android.app.Application
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import ru.neuroscribe.app.audio.AudioDecoder
import ru.neuroscribe.app.engine.TranscriptionEngine
import java.io.File
import kotlin.math.abs

/** Runs the real Android decoder and bundled native models; no mocked inference. */
class PipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(name: String): File = File(context.cacheDir, "test-$name").also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun run(name: String, language: String = "ru") = TranscriptionEngine(context).process(fixture(name), language, { _, _ -> }, {})
    private fun save(name: String, result: ru.neuroscribe.app.engine.EngineResult) {
        val baseline=JSONObject(instrumentation.context.assets.open("transcripts-v03-baseline.json").bufferedReader().use { it.readText() })
        assertEquals("$name final transcript", ru.neuroscribe.app.core.Transcript.fromJson(baseline.getJSONObject("test-$name").toString()), result.turns)
        val report = JSONObject().put("stageMs",JSONObject(result.timingsMs)).put("result", JSONObject(ru.neuroscribe.app.core.Transcript.toJson(result.turns)))
            .put("durationMs",result.durationMs).put("processingMs",result.processingMs)
            .put("llmCalls",result.llmCalls).put("rejectedEdits",result.rejectedEdits).put("overlap",result.overlap)
        File(context.filesDir,"test-$name.json").writeText(report.toString(2))
    }

    @Test fun wavAndAacDecodeWithCorrectDuration() {
        val wav = AudioDecoder.decode(fixture("russian.wav")) {}
        val aac = AudioDecoder.decode(fixture("russian.m4a")) {}
        assertEquals(164490, wav.size)
        assertTrue("AAC duration ${aac.size}", abs(aac.size-wav.size) < 16000 / 5)
        assertTrue(aac.all { it.isFinite() && it in -1f..1f })
        assertTrue(aac.any { abs(it)>0.01f })
    }
    @Test fun stereoMp3DecodesToMonoWithoutLosingDuration() {
        val pcm = AudioDecoder.decode(fixture("conversation.mp3")) {}
        assertTrue("MP3 duration ${pcm.size / 16000.0}", abs(pcm.size / 16000.0 - 169.056) < 0.2)
        assertTrue(pcm.all { it.isFinite() && it in -1f..1f })
    }
    @Test fun invalidAndEmptyFilesFailWithoutNativeCrash() {
        for (name in listOf("broken.wav","empty.wav")) {
            try { AudioDecoder.decode(fixture(name)) {}; fail("Accepted $name") } catch (_: Exception) { }
        }
    }
    @Test fun silenceDoesNotProduceInventedWords() {
        val result = run("silence.wav")
        assertEquals(5000L,result.durationMs)
        assertTrue(result.turns.isEmpty())
        assertEquals(0,result.llmCalls)
        save("silence",result)
    }
    @Test fun russianRunsOfflineWithRealLlmAndValidTimeBounds() {
        assertFalse(context.packageManager.getPackageInfo(context.packageName,4096).requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        val result = run("russian.wav")
        assertTrue(result.turns.joinToString(" ") { it.text }.contains("Добрый",true))
        assertTrue(result.llmCalls>0)
        assertEquals(1,result.turns.map { it.speakerId }.distinct().size)
        result.turns.forEach { assertTrue(it.startMs>=0 && it.endMs<=result.durationMs && it.endMs>it.startMs) }
        save("russian",result)
    }
    @Test fun twoSpeakerFixtureKeepsStableIdsAcrossFourTurns() {
        val result = run("two-speakers.wav","en")
        assertEquals(listOf("speaker_1","speaker_1","speaker_2","speaker_2"),result.turns.map { it.speakerId })
        assertEquals(4,result.llmCalls)
        assertTrue(result.turns.last().startMs>12000)
        save("two-speakers",result)
    }
    @Test fun cancellationAtStageBoundaryReleasesNativeModelsAndAllowsRetry() {
        var stage = ""
        try {
            TranscriptionEngine(context).process(fixture("russian.wav"),"ru",{ value,_ -> stage=value },{
                if (stage.startsWith("Определяем говорящих")) throw CancellationException("test cancellation")
            })
            fail("Cancellation was ignored")
        } catch (_: CancellationException) { }
        assertTrue(run("russian.wav").turns.isNotEmpty())
    }
    @Test fun failedImportKeepsThePreviousRecordingUsable() {
        lateinit var model: ScribeViewModel
        val input=fixture("russian.wav")
        instrumentation.runOnMainSync {
            model=ScribeViewModel(context.applicationContext as Application)
            model.importAudio(Uri.fromFile(input))
        }
        awaitIdle(model)
        assertNull(model.state.value.error)
        val before=model.state.value
        val audioHash=model.audioFile.readBytes().contentHashCode()
        instrumentation.runOnMainSync { model.importAudio(Uri.fromFile(File(context.cacheDir,"nonexistent-test.wav"))) }
        awaitIdle(model)
        assertNotNull(model.state.value.error)
        assertEquals(before.fileName,model.state.value.fileName)
        assertEquals(audioHash,model.audioFile.readBytes().contentHashCode())
    }
    @Test fun activityRecreationKeepsOneProcessingJobAndShowsTheResult() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model:ScribeViewModel
            val input=fixture("russian.wav")
            scenario.onActivity {
                model=ViewModelProvider(it)[ScribeViewModel::class.java]
                model.importAudio(Uri.fromFile(input))
            }
            awaitIdle(model)
            scenario.onActivity { model.transcribe("ru") }
            scenario.recreate()
            scenario.onActivity { assertSame(model,ViewModelProvider(it)[ScribeViewModel::class.java]) }
            awaitIdle(model)
            assertTrue(model.state.value.finished)
            assertNull(model.state.value.error)
            assertEquals(1,model.state.value.llmCalls)
        }
    }
    private fun awaitIdle(model:ScribeViewModel) {
        val until=System.nanoTime()+90_000_000_000
        while (model.state.value.busy && System.nanoTime()<until) Thread.sleep(20)
        assertFalse("Operation did not finish",model.state.value.busy)
    }
}
