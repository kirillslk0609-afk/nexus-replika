package ru.neuroscribe.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import ru.neuroscribe.app.audio.AudioDecoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class DecoderRegressionTest {
    @Test fun asyncDecodingPreservesEveryPcmSampleAndCancellation() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val rows = JSONArray()
        val names = listOf("russian.m4a", "conversation.mp3", "conversation-second.mp3",
            "validation-en.mp3", "unseen-ru.mp3", "russian.wav", "two-speakers.wav", "noise/garden-clean.wav")
        for ((i, name) in names.withIndex()) {
            val file = File(ctx.cacheDir, "decoder-" + name.replace('/', '-'))
            inst.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            lateinit var before: FloatArray
            lateinit var after: FloatArray
            var beforeMs = 0.0
            var afterMs = 0.0
            fun run(fast: Boolean) {
                val start = System.nanoTime()
                val pcm = AudioDecoder.decode(file, fast) {}
                val ms = (System.nanoTime() - start) / 1e6
                if (fast) { after = pcm; afterMs = ms } else { before = pcm; beforeMs = ms }
            }
            if (i % 2 == 0) { run(false); run(true) } else { run(true); run(false) }
            assertFalse("Decoder worker leaked",Thread.getAllStackTraces().keys.any { it.name=="neuroscribe-decoder" && it.isAlive })
            assertEquals("$name sample count", before.size, after.size)
            for (j in before.indices) {
                if (before[j].toRawBits() != after[j].toRawBits()) fail("$name sample $j changed")
            }
            val bytes = ByteBuffer.allocate(before.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            before.forEach { bytes.putInt(it.toRawBits()) }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
            rows.put(JSONObject().put("fixture",name).put("samples",before.size).put("pcmFloat32LeSha256",hash)
                .put("baselineMs",beforeMs).put("optimizedMs",afterMs).put("bitExact",true))
            File(ctx.filesDir,"speed-decoder.json").writeText(rows.toString(2))
            if (i == 0) {
                val cancellation = IllegalStateException("test cancellation")
                var checks = 0
                try {
                    AudioDecoder.decode(file) { if (++checks == 3) throw cancellation }
                    fail("Expected cancellation while decoding compressed input")
                } catch (e: IllegalStateException) { assertSame(cancellation,e) }
                val retry = AudioDecoder.decode(file) {}
                assertTrue("Cancelled decoder must release its resources",after.contentEquals(retry))
                assertFalse("Cancelled decoder worker leaked",Thread.getAllStackTraces().keys.any { it.name=="neuroscribe-decoder" && it.isAlive })
            }
        }
    }
}
