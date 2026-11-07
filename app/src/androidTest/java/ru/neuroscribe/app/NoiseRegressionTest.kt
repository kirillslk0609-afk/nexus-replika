package ru.neuroscribe.app

import android.os.Debug
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import ru.neuroscribe.app.engine.SpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.app.audio.AudioDecoder
import ru.neuroscribe.app.engine.ModelStore
import java.io.File
import java.util.Locale

/** Labelled ASR comparison on identical PCM. References are test-only and never reach the recognizer. */
class NoiseRegressionTest {
    @Test fun cleanControlsAndDifferentNoiseTypesKeepWordAccuracy() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0))
        val cases = JSONArray(inst.context.assets.open("noise/reference.json").bufferedReader().use { it.readText() })
        val previous = JSONObject(inst.context.assets.open("noise/baseline-v03.json").bufferedReader().use { it.readText() }).getJSONArray("cases")
        val previousByFile = (0 until previous.length()).map { previous.getJSONObject(it) }.associateBy { it.getString("file") }
        val models = ModelStore(context); models.prepare({}, {})
        val baselineRun = InstrumentationRegistry.getArguments().getString("noiseRecordBaseline") == "true"
        val reportName = if (baselineRun) "asr-noise-baseline.json" else "asr-noise-regression.json"
        val reports = JSONArray()
        var maxPssKb = 0L
        for (language in listOf("ru", "en")) {
            val recognizer = SpeechRecognizer(models, language, 4)
            try {
                for (index in 0 until cases.length()) {
                    val case = cases.getJSONObject(index)
                    if (case.getString("language") != language) continue
                    val file = File(context.cacheDir, "noise-test.wav")
                    inst.context.assets.open(case.getString("file")).use { input -> file.outputStream().use { input.copyTo(it) } }
                    val pcm = AudioDecoder.decode(file) {}
                    val retriesBefore = recognizer.retryCount
                    val started = System.nanoTime()
                    val text = recognizer.recognize(pcm, {}, retryLoops = !baselineRun)
                    if (!baselineRun) assertEquals("ASR changed from 0.3: ${case.getString("file")}", previousByFile.getValue(case.getString("file")).getString("text"), text)
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val reference = case.getString("reference")
                    val errors = distance(words(reference), words(text))
                    maxPssKb = maxOf(maxPssKb, Debug.getPss())
                    reports.put(JSONObject(case.toString()).put("text", text).put("errors", errors)
                        .put("referenceWords", words(reference).size).put("durationMs", pcm.size * 1000L / 16000)
                        .put("recognitionMs", elapsed).put("retries", recognizer.retryCount - retriesBefore))
                    File(context.filesDir, reportName).writeText(JSONObject()
                        .put("cases", reports).put("airplaneMode", true).put("maxSampledPssKb", maxPssKb).toString(2))
                }
            } finally { recognizer.close() }
        }
        assertEquals(cases.length(), reports.length())
        if (baselineRun) return
        val rows = (0 until reports.length()).map { reports.getJSONObject(it) }
        // Protect clean speech and each noise category in each language, not just a pooled score.
        for (language in listOf("ru", "en")) for (noise in listOf("clean", "white", "pink", "hum")) {
            val group = rows.filter { it.getString("language") == language && it.getString("noise") == noise }
            assertTrue("$language/$noise has no test cases", group.isNotEmpty())
            val before = group.sumOf { it.getJSONObject("baseline").getInt("errors") }
            val after = group.sumOf { it.getInt("errors") }
            assertTrue("$language/$noise WER regression: $before -> $after errors", after <= before)
        }
        val noisy = rows.filter { it.getString("noise") != "clean" }
        assertTrue("No improvement in noisy speech", noisy.sumOf { it.getInt("errors") } < noisy.sumOf { it.getJSONObject("baseline").getInt("errors") })
    }

    private fun words(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
    private fun distance(reference: List<String>, actual: List<String>): Int {
        var previous = IntArray(actual.size + 1) { it }
        reference.forEachIndexed { i, word ->
            val row = IntArray(actual.size + 1); row[0] = i + 1
            actual.forEachIndexed { j, value -> row[j + 1] = minOf(row[j] + 1, previous[j + 1] + 1, previous[j] + if (word == value) 0 else 1) }
            previous = row
        }
        return previous.last()
    }
}
