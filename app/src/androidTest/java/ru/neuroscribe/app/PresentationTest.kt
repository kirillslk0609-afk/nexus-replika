package ru.neuroscribe.app

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Button
import android.content.ClipboardManager
import android.graphics.Rect
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.neuroscribe.app.core.Transcript
import java.io.File

/** Interaction checks for the actual Activity. User state is restored even after assertion failures. */
class PresentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val saved = File(context.filesDir, "last-result.json")
    private val audio = File(context.filesDir, "current-audio")
    private var previousAudio: ByteArray? = null
    private var previousResult: ByteArray? = null

    @Before fun backup() {
        previousAudio = audio.takeIf { it.exists() }?.readBytes()
        previousResult = saved.takeIf { it.exists() }?.readBytes()
        saved.delete()
    }
    @After fun restore() {
        previousAudio?.let { audio.writeBytes(it) } ?: audio.delete()
        previousResult?.let { saved.writeBytes(it) } ?: saved.delete()
    }
    private fun fixture(name: String) = File(context.cacheDir, "presentation-$name").also { f ->
        instrumentation.context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
    }
    private fun awaitIdle(model: ScribeViewModel) {
        val until = System.nanoTime() + 120_000_000_000L
        while (model.state.value.busy && System.nanoTime() < until) Thread.sleep(30)
        assertFalse("Operation timed out", model.state.value.busy)
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // Allow a frame to be presented after layout changes; assertions above use actual View state.
        Thread.sleep(160)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "design-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun allViews(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { allViews(root.getChildAt(it)) } else emptyList()

    @Test fun emptyImportAndLanguageSurviveRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: ScribeViewModel
            scenario.onActivity { activity ->
                assertEquals("Реплика", context.applicationInfo.loadLabel(context.packageManager).toString())
                assertTrue(activity.findViewById<Button>(R.id.select_audio).isShown)
                assertFalse(activity.findViewById<Button>(R.id.transcribe).isShown)
                assertFalse(activity.findViewById<Button>(R.id.export_json).isShown)
                model = ViewModelProvider(activity)[ScribeViewModel::class.java]
            }
            screenshot("empty")
            val file = fixture("russian.wav")
            scenario.onActivity { model.importAudio(Uri.fromFile(file)) }
            awaitIdle(model)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<Button>(R.id.transcribe).isShown)
                activity.findViewById<Spinner>(R.id.audio_language).setSelection(1)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame(model, ViewModelProvider(activity)[ScribeViewModel::class.java])
                assertEquals(1, activity.findViewById<Spinner>(R.id.audio_language).selectedItemPosition)
                assertTrue(activity.findViewById<Button>(R.id.transcribe).isEnabled)
            }
            screenshot("ready")
            scenario.onActivity { model.importAudio(Uri.fromFile(File(context.cacheDir, "missing-ui-fixture.wav"))) }
            awaitIdle(model)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<TextView>(R.id.error_message).isShown)
                assertTrue(activity.findViewById<Button>(R.id.transcribe).isEnabled)
                assertArrayEquals(file.readBytes(), model.audioFile.readBytes())
            }
            screenshot("error")
        }
    }

    @Test fun processingCancelRetryPlaybackAndJsonRemainUsable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: ScribeViewModel
            val input = fixture("russian.wav")
            scenario.onActivity {
                model = ViewModelProvider(it)[ScribeViewModel::class.java]
                model.importAudio(Uri.fromFile(input))
            }
            awaitIdle(model)
            scenario.onActivity { it.findViewById<Button>(R.id.transcribe).performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertTrue(model.state.value.busy)
                assertTrue(it.findViewById<Button>(R.id.cancel_processing).isShown)
                assertFalse(it.findViewById<Button>(R.id.select_audio).isShown)
            }
            screenshot("processing")
            scenario.onActivity { it.findViewById<Button>(R.id.cancel_processing).performClick() }
            awaitIdle(model)
            assertFalse(model.state.value.finished)
            scenario.onActivity { it.findViewById<Button>(R.id.transcribe).performClick() }
            scenario.recreate()
            awaitIdle(model)
            val expected = JSONObject(instrumentation.context.assets.open("transcripts-v03-baseline.json").bufferedReader().use { it.readText() })
            assertEquals(Transcript.fromJson(expected.getJSONObject("test-russian").toString()), model.state.value.turns)
            assertNull(model.state.value.error)
            assertTrue(model.state.value.finished)
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<Button>(R.id.transcribe).isShown)
                assertTrue(activity.findViewById<Button>(R.id.export_json).isShown)
                activity.findViewById<View>(R.id.processing_options).performClick()
                assertTrue(activity.findViewById<Button>(R.id.transcribe).isShown)
                activity.findViewById<View>(R.id.processing_options).performClick()
                val play = allViews(activity.findViewById(R.id.transcript_turns)).filterIsInstance<ImageButton>().first()
                play.performClick()
                assertEquals("Приостановить реплику", play.contentDescription)
                play.performClick()
                assertEquals("Продолжить реплику", play.contentDescription)
            }
            // Resume, observe real playback progress, pause without losing position, then resume.
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.transcript_turns).findViewWithTag<ImageButton>("play-turn-0").performClick()
                activity.findViewById<Button>(R.id.copy_transcript).performClick()
                val copied = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                model.state.value.turns.forEach { assertTrue(copied.contains(it.text)) }
                assertTrue(copied.contains("Говорящий 1"))
            }
            waitForProgress(scenario, 30)
            var pausedProgress = 0
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.transcript_turns).findViewWithTag<ImageButton>("play-turn-0").performClick()
                pausedProgress = activity.findViewById<View>(R.id.transcript_turns).findViewWithTag<ProgressBar>("playback-bar-0").progress
            }
            Thread.sleep(350)
            scenario.onActivity { activity ->
                assertEquals(pausedProgress, activity.findViewById<View>(R.id.transcript_turns).findViewWithTag<ProgressBar>("playback-bar-0").progress)
                activity.findViewById<View>(R.id.transcript_turns).findViewWithTag<ImageButton>("play-turn-0").performClick()
                val scrollBounds = Rect(); val dockBounds = Rect()
                activity.findViewById<View>(R.id.main_scroll).getGlobalVisibleRect(scrollBounds)
                activity.findViewById<View>(R.id.action_dock).getGlobalVisibleRect(dockBounds)
                assertTrue("Dock overlays transcript", scrollBounds.bottom <= dockBounds.top)
            }
            waitForProgress(scenario, pausedProgress + 30)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<View>(R.id.transcript_turns).findViewWithTag<View>("playback-progress-0").isShown)
            }
            val export = File(context.cacheDir, "design-export.json")
            runBlocking { model.export(Uri.fromFile(export)) }
            val json = JSONObject(export.readText())
            assertEquals(setOf("transcript"), json.keys().asSequence().toSet())
            assertEquals(model.state.value.turns, Transcript.fromJson(json.toString()))
            File(context.filesDir, "design-ui-result.json").writeText(json.toString(2))
            screenshot("result")
        }
    }

    private fun waitForProgress(scenario: ActivityScenario<MainActivity>, minimum: Int) {
        val deadline = System.nanoTime() + 8_000_000_000L
        var observed = 0
        while (observed < minimum && System.nanoTime() < deadline) {
            scenario.onActivity { observed = it.findViewById<View>(R.id.transcript_turns).findViewWithTag<ProgressBar>("playback-bar-0").progress }
            Thread.sleep(80)
        }
        assertTrue("MediaPlayer progress never advanced: $observed < $minimum", observed >= minimum)
    }

    @Test fun twoSpeakerResultRestoresAndOptionsRemainAvailable() {
        fixture("two-speakers.wav").copyTo(audio, overwrite = true)
        val baseline = JSONObject(instrumentation.context.assets.open("transcripts-v03-baseline.json").bufferedReader().use { it.readText() })
        saved.writeText(JSONObject().put("fileName", "Диалог двух говорящих.wav")
            .put("result", baseline.getJSONObject("test-two-speakers"))
            .put("durationMs", 16000).put("processingMs", 25616).put("llmCalls", 4)
            .put("rejectedEdits", 0).put("overlap", false).toString())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ScribeViewModel::class.java]
                assertEquals(2, model.state.value.turns.map { it.speakerId }.distinct().size)
                val speakerLabels = allViews(activity.findViewById(R.id.transcript_turns)).filterIsInstance<TextView>().filter { it.text.startsWith("Говорящий") }
                assertEquals(4, speakerLabels.size)
                assertNotEquals(speakerLabels.first().text, speakerLabels.last().text)
                assertTrue(activity.findViewById<TextView>(R.id.recording_summary).text.startsWith("2 говорящих"))
                activity.findViewById<View>(R.id.processing_options).performClick()
            }
            scenario.recreate()
            scenario.onActivity { activity -> assertTrue(activity.findViewById<Button>(R.id.transcribe).isShown) }
            scenario.onActivity {
                it.findViewById<View>(R.id.processing_options).performClick()
                assertFalse(it.findViewById<Button>(R.id.transcribe).isShown)
            }
            screenshot("two-speakers")
        }
    }
}
