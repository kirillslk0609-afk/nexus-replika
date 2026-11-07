package ru.neuroscribe.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.widget.ScrollView
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Layout-only mock content matching the approved picture; NOT an ASR quality fixture. */
class VisualLayoutTest {
    @Test fun approvedMockPlaybackLayout() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val audio = File(context.filesDir, "current-audio")
        val saved = File(context.filesDir, "last-result.json")
        val previousAudio = audio.takeIf { it.exists() }?.readBytes()
        val previousResult = saved.takeIf { it.exists() }?.readBytes()
        try {
            val samples = 42 * 16000
            val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            wav.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            wav.put("data".toByteArray()).putInt(samples * 2)
            audio.writeBytes(wav.array()) // Silence for deterministic, non-private visual playback.
            saved.writeText("""{"fileName":"План проекта.wav","durationMs":42000,"processingMs":0,"llmCalls":0,"rejectedEdits":0,"overlap":false,"result":{"transcript":[
                {"speaker_id":"speaker_1","start_ms":1000,"end_ms":8000,"text":"Давайте обсудим план работы. Что нужно подготовить сегодня?"},
                {"speaker_id":"speaker_2","start_ms":9000,"end_ms":25000,"text":"Сначала закончим приложение, затем проверим распознавание на разных записях."},
                {"speaker_id":"speaker_1","start_ms":26000,"end_ms":42000,"text":"Хорошо. После проверки сохраним результат и подготовим демонстрацию."}
            ]}}""")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.transcript_turns).findViewWithTag<ImageButton>("play-turn-1").performClick()
                }
                val deadline = System.nanoTime() + 15_000_000_000L
                var progress = 0
                while (progress < 375 && System.nanoTime() < deadline) {
                    scenario.onActivity { activity ->
                        progress = activity.findViewById<View>(R.id.transcript_turns).findViewWithTag<ProgressBar>("playback-bar-1").progress
                    }
                    Thread.sleep(40)
                }
                assertTrue("Playback did not reach reference position", progress >= 375)
                var top = 0; var bottom = 0; var width = 0; var height = 0; var density = 0f
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.main_scroll).parent as View
                    top = root.paddingTop; bottom = root.paddingBottom
                    width = root.width; height = root.height; density = activity.resources.displayMetrics.density
                }
                val bitmap = inst.uiAutomation.takeScreenshot()
                File(context.filesDir, "visual-full.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val content = Bitmap.createBitmap(bitmap, 0, top, width, height - top - bottom)
                File(context.filesDir, "visual-content.png").outputStream().use { content.compress(Bitmap.CompressFormat.PNG, 100, it) }
                content.recycle(); bitmap.recycle()
                scenario.onActivity { activity ->
                    val scroll = activity.findViewById<ScrollView>(R.id.main_scroll)
                    val scrollBounds = Rect(); val dockBounds = Rect()
                    scroll.getGlobalVisibleRect(scrollBounds)
                    activity.findViewById<View>(R.id.action_dock).getGlobalVisibleRect(dockBounds)
                    assertTrue("Dock overlays transcript", scrollBounds.bottom <= dockBounds.top)
                    assertTrue("Transcript area too small", scrollBounds.height() > 100 * density)
                    scroll.fullScroll(View.FOCUS_DOWN)
                }
                Thread.sleep(300)
                val lower = inst.uiAutomation.takeScreenshot()
                File(context.filesDir, "visual-bottom.png").outputStream().use { lower.compress(Bitmap.CompressFormat.PNG, 100, it) }
                lower.recycle()
                File(context.filesDir, "visual-geometry.json").writeText(JSONObject()
                    .put("width", width).put("height", height).put("topInset", top).put("bottomInset", bottom)
                    .put("density", density).put("contentDpWidth", width / density)
                    .put("contentDpHeight", (height - top - bottom) / density)
                    .put("data", "Mock transcript + silent WAV: visual layout only, not recognition output").toString(2))
                // Starting another entry resets the previous progress, keeping only one active.
                scenario.onActivity { activity ->
                    val turns = activity.findViewById<View>(R.id.transcript_turns)
                    turns.findViewWithTag<ImageButton>("play-turn-0").performClick()
                    assertFalse(turns.findViewWithTag<View>("playback-progress-1").isShown)
                    assertTrue(turns.findViewWithTag<View>("playback-progress-0").isShown)
                }
            }
        } finally {
            previousAudio?.let { audio.writeBytes(it) } ?: audio.delete()
            previousResult?.let { saved.writeBytes(it) } ?: saved.delete()
        }
    }
}
