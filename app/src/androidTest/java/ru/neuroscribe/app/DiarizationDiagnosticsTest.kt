package ru.neuroscribe.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import ru.neuroscribe.app.audio.AudioDecoder
import ru.neuroscribe.app.engine.ModelStore
import ru.neuroscribe.app.engine.StableDiarizer
import java.io.File

/** Optional diagnostic run: captures real Android embeddings to investigate cross-platform differences. */
class DiarizationDiagnosticsTest {
    @Test fun exportRealAndroidClusteringInputs() {
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        val models=ModelStore(ctx);models.prepare({}, {})
        for (name in listOf("validation-en.mp3","unseen-ru.mp3","unseen-en.mp3")) {
            val file=File(ctx.cacheDir,"diagnostic-$name")
            inst.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            val pcm=AudioDecoder.decode(file) {}
            StableDiarizer(models,4).process(pcm,onClustering={vectors,seconds,labels ->
                val matrix=JSONArray();vectors.forEach { vector -> val row=JSONArray();vector.forEach { row.put(it.toDouble()) };matrix.put(row) }
                val weights=JSONArray();seconds.forEach { weights.put(it) }
                val assignments=JSONArray();labels.forEach { assignments.put(it) }
                File(ctx.filesDir,"diagnostic-$name.json").writeText(JSONObject().put("fixture",name).put("vectors",matrix).put("seconds",weights).put("labels",assignments).toString())
            })
        }
    }
}
