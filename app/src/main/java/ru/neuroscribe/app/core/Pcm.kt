package ru.neuroscribe.app.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Streaming anti-alias FIR + interpolation, with compensated FIR delay and absolute timing. */
class PcmCollector(private val sourceRate: Int, private val maxSeconds: Int = 900) {
    private var output = FloatArray(16000 * 10)
    private var size = 0
    private var sourceIndex = 0L
    private var nextPosition = 0.0
    private var previous = 0f
    private val filter = if (sourceRate > 16000) LowPass(sourceRate) else null
    private var filterInputs = 0L
    private var rawCount = 0L
    private var lastInput = 0f
    private var finished = false
    init { require(sourceRate > 0) }
    fun add(value: Float) {
        check(!finished) { "PCM collector already finished" }
        val sample = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
        rawCount++
        require(rawCount <= sourceRate.toLong() * maxSeconds) { "В первой версии поддерживаются записи до $maxSeconds секунд." }
        lastInput = sample
        filtered(sample)
    }
    private fun filtered(sample: Float) {
        val fir=filter
        if (fir==null) resample(sample)
        else {
            val value=fir.push(sample)
            if (filterInputs++ >= fir.delay) resample(value)
        }
    }
    private fun resample(sample: Float) {
        while (nextPosition <= sourceIndex) {
            val fraction = (nextPosition - (sourceIndex - 1)).coerceIn(0.0, 1.0)
            append(if (sourceIndex == 0L) sample else (previous + (sample - previous) * fraction).toFloat())
            nextPosition += sourceRate / 16000.0
        }
        previous = sample
        sourceIndex++
    }
    private fun append(value: Float) {
        require(size < 16000 * maxSeconds) { "В первой версии поддерживаются записи до $maxSeconds секунд." }
        if (size == output.size) output = output.copyOf(minOf(output.size * 2, 16000 * maxSeconds))
        output[size++] = value
    }
    fun finish(): FloatArray {
        if (!finished) {
            if (rawCount>0) repeat(filter?.delay ?: 0) { filtered(lastInput) }
            finished=true
        }
        val expected = (rawCount * 16000L / sourceRate).toInt()
        while (size < expected) append(previous)
        return output.copyOf(minOf(size, expected))
    }
}

private class LowPass(rate: Int) {
    private val count = ((rate * 24 / 16000) or 1).coerceAtLeast(31)
    val delay = count / 2
    private val coefficients = DoubleArray(count)
    private val history = FloatArray(count)
    private var cursor=0
    private var initialized=false
    init {
        val cutoff=7200.0/rate
        for(i in coefficients.indices) {
            val x=i-delay
            val sinc=if(x==0) 2*cutoff else sin(2*PI*cutoff*x)/(PI*x)
            coefficients[i]=sinc*(0.54-0.46*cos(2*PI*i/(count-1)))
        }
        val sum=coefficients.sum()
        for(i in coefficients.indices) coefficients[i]/=sum
    }
    fun push(value:Float):Float {
        if(!initialized) { history.fill(value);initialized=true }
        history[cursor]=value
        var index=cursor;var sum=0.0
        for(coefficient in coefficients) {
            sum+=coefficient*history[index]
            if(--index<0) index=count-1
        }
        cursor=(cursor+1)%count
        return sum.toFloat().coerceIn(-1f,1f)
    }
}

object WavReader {
    fun read(file: File, checkCancelled: () -> Unit): FloatArray = RandomAccessFile(file, "r").use { input ->
        fun text(n: Int) = ByteArray(n).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        fun u16() = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
        fun u32() = java.lang.Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        require(text(4) == "RIFF") { "Неподдерживаемый WAV: ожидается RIFF." }
        u32(); require(text(4) == "WAVE")
        var format = 0; var channels = 0; var rate = 0; var bits = 0
        var dataOffset = -1L; var dataLength = 0L
        while (input.filePointer + 8 <= input.length()) {
            val chunk = text(4); val length = u32(); val start = input.filePointer
            require(length <= input.length() - start) { "Повреждённый WAV-файл." }
            when (chunk) {
                "fmt " -> {
                    require(length >= 16)
                    format = u16(); channels = u16(); rate = u32().toInt(); u32(); u16(); bits = u16()
                    if (format == 65534 && length >= 40) { input.seek(start + 24); format = u16() }
                }
                "data" -> { dataOffset = start; dataLength = length }
            }
            input.seek(start + length + length % 2)
        }
        require(dataOffset >= 0 && channels in 1..8 && rate in 8000..192000) { "WAV: неверный заголовок." }
        require((format == 1 && bits in listOf(8, 16, 24, 32)) || (format == 3 && bits == 32)) {
            "WAV должен содержать PCM 8/16/24/32 bit или float32."
        }
        val collector = PcmCollector(rate)
        val frameBytes = channels * (bits / 8)
        require(dataLength % frameBytes == 0L) { "WAV: неполный аудиокадр." }
        require(dataLength / frameBytes <= rate.toLong() * 900) { "Запись длиннее 15 минут." }
        input.seek(dataOffset)
        val bytes = ByteArray((65536 / frameBytes) * frameBytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var remaining = dataLength
        while (remaining > 0) {
            checkCancelled()
            val count = minOf(remaining, bytes.size.toLong()).toInt()
            input.readFully(bytes, 0, count)
            buffer.clear(); buffer.limit(count)
            repeat(count / frameBytes) {
                var sum = 0f
                repeat(channels) {
                    sum += when {
                        format == 3 -> buffer.float
                        bits == 8 -> ((buffer.get().toInt() and 255) - 128) / 128f
                        bits == 16 -> buffer.short / 32768f
                        bits == 24 -> {
                            val value = (buffer.get().toInt() and 255) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)
                            value / 8388608f
                        }
                        else -> buffer.int / 2147483648f
                    }
                }
                collector.add(sum / channels)
            }
            remaining -= count
        }
        collector.finish()
    }
}
