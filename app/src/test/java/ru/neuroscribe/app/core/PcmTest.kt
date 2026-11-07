package ru.neuroscribe.app.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class PcmTest {
    @Test fun resamplingPreservesDurationAcrossCommonRates() {
        for (rate in listOf(8000, 16000, 22050, 44100, 48000)) {
            val sink = PcmCollector(rate)
            repeat(rate * 2) { sink.add(0.5f) }
            val samples = sink.finish()
            assertEquals("rate=$rate", 32000, samples.size)
            assertTrue(samples.all { kotlin.math.abs(it - 0.5f) < 0.0001 })
        }
    }
    @Test fun wavStereoDownmixHandlesExtraOddSizedChunk() {
        val pcm = ByteBuffer.allocate(8000 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(8000) { pcm.putShort(16384); pcm.putShort(0) }
        val wav = ByteBuffer.allocate(44 + 10 + pcm.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()); wav.putInt(wav.capacity() - 8); wav.put("WAVE".toByteArray())
        wav.put("JUNK".toByteArray()); wav.putInt(1); wav.put(0); wav.put(0)
        wav.put("fmt ".toByteArray()); wav.putInt(16); wav.putShort(1); wav.putShort(2)
        wav.putInt(8000); wav.putInt(32000); wav.putShort(4); wav.putShort(16)
        wav.put("data".toByteArray()); wav.putInt(pcm.capacity()); wav.put(pcm.array())
        val file = Files.createTempFile("stereo", ".wav").toFile()
        try {
            file.writeBytes(wav.array())
            val result = WavReader.read(file) {}
            assertEquals(16000, result.size)
            assertTrue(result.all { kotlin.math.abs(it - 0.25f) < 0.0001 })
        } finally { file.delete() }
    }
    @Test fun durationLimitPreventsUnboundedMemoryGrowth() {
        val sink = PcmCollector(16000, maxSeconds = 1)
        repeat(16000) { sink.add(0f) }
        assertThrows(IllegalArgumentException::class.java) { sink.add(0f) }
    }
    @Test fun nonFiniteSamplesAreSanitized() {
        val sink = PcmCollector(16000)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 5f, -5f).forEach(sink::add)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, -1f), sink.finish(), 0f)
    }
    @Test fun bufferedWavReadsAllPcmWidthsAcrossBlockBoundaries() {
        for ((format,bits) in listOf(1 to 8,1 to 16,1 to 24,1 to 32,3 to 32)) {
            val frames=70001
            val length=frames*(bits/8)
            val bytes=ByteBuffer.allocate(44+length).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray());bytes.putInt(36+length);bytes.put("WAVEfmt ".toByteArray())
            bytes.putInt(16);bytes.putShort(format.toShort());bytes.putShort(1);bytes.putInt(16000)
            bytes.putInt(16000*bits/8);bytes.putShort((bits/8).toShort());bytes.putShort(bits.toShort())
            bytes.put("data".toByteArray());bytes.putInt(length)
            repeat(frames) {
                when {
                    format==3 -> bytes.putFloat(-0.5f)
                    bits==8 -> bytes.put(64)
                    bits==16 -> bytes.putShort(-16384)
                    bits==24 -> { bytes.put(0);bytes.put(0);bytes.put(0xc0.toByte()) }
                    else -> bytes.putInt(-1073741824)
                }
            }
            val file=Files.createTempFile("width-$bits", ".wav").toFile()
            try {
                file.writeBytes(bytes.array())
                val pcm=WavReader.read(file) {}
                assertEquals(frames,pcm.size)
                assertTrue(pcm.all { it == -0.5f })
            } finally { file.delete() }
        }
    }
    @Test fun downsamplingRejectsAboveNyquistToneAndPreservesSpeechBand() {
        fun tone(hz:Double):FloatArray {
            val sink=PcmCollector(48000)
            repeat(48000) { sink.add((0.5*kotlin.math.sin(2*Math.PI*hz*it/48000)).toFloat()) }
            return sink.finish()
        }
        fun rms(x:FloatArray)=kotlin.math.sqrt(x.drop(200).dropLast(200).map { it.toDouble()*it }.average())
        val voice=tone(1000.0);val ultrasonic=tone(12000.0)
        assertEquals(16000,voice.size)
        assertTrue(rms(voice)>0.3)
        assertTrue("12 kHz must not alias to 4 kHz",rms(ultrasonic)<0.01)
    }
    @Test fun filterDelayDoesNotShiftAnImpulseOrDropShortAudio() {
        val sink=PcmCollector(48000)
        repeat(4800) { sink.add(if(it==2400) 1f else 0f) }
        val result=sink.finish()
        assertEquals(1600,result.size)
        assertEquals(800,result.indices.maxBy { result[it] })
        val short=PcmCollector(48000)
        repeat(9) { short.add(.5f) }
        assertArrayEquals(floatArrayOf(.5f,.5f,.5f),short.finish(),.0001f)
    }
}
