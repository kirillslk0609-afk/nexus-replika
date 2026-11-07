package ru.neuroscribe.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import ru.neuroscribe.app.core.PcmCollector
import ru.neuroscribe.app.core.WavReader
import java.io.File
import java.nio.ByteOrder
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object AudioDecoder {
    fun decode(file: File, checkCancelled: () -> Unit): FloatArray = decode(file, true, checkCancelled)

    /** The synchronous path is retained for paired PCM/performance regression tests. */
    fun decode(file: File, optimized: Boolean, checkCancelled: () -> Unit): FloatArray {
        val header = file.inputStream().use { input ->
            val bytes = ByteArray(12)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) break
                offset += count
            }
            bytes.copyOf(offset)
        }
        if (header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF") {
            return WavReader.read(file, checkCancelled)
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В файле нет поддерживаемой аудиодорожки. Попробуйте WAV, MP3 или M4A.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                require(format.getLong(MediaFormat.KEY_DURATION) <= 900_000_000) { "Запись длиннее 15 минут." }
            }
            if (optimized) return decodeAsync(extractor, format, checkCancelled)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var collector: PcmCollector? = null
            var inputEnded = false
            var lastOutput = System.nanoTime()
            val info = MediaCodec.BufferInfo()
            while (true) {
                checkCancelled()
                require(System.nanoTime() - lastOutput < 30_000_000_000L) { "Декодер аудио перестал отвечать." }
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        val newRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        require(collector == null || newRate == rate) { "Смена частоты внутри записи не поддерживается." }
                        rate = newRate
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    else -> if (index >= 0) {
                        lastOutput = System.nanoTime()
                        try {
                            if (info.size > 0) {
                                require(channels in 1..8 && rate in 8000..192000)
                                require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    "Неподдерживаемый PCM-формат декодера: $encoding"
                                }
                                val output = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                                output.position(info.offset); output.limit(info.offset + info.size)
                                val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val sink = collector ?: PcmCollector(rate).also { collector = it }
                                while (output.remaining() >= channels * bytes) {
                                    var sum = 0f
                                    repeat(channels) { sum += if (bytes == 4) output.float else output.short / 32768f }
                                    sink.add(sum / channels)
                                }
                            }
                        } finally { decoder.releaseOutputBuffer(index, false) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return collector?.finish() ?: error("Аудиозапись пуста.")
        } finally {
            codec?.runCatching { stop() }
            codec?.release()
            extractor.release()
        }
    }

    private sealed interface OutputEvent {
        data class Format(val rate: Int, val channels: Int, val encoding: Int) : OutputEvent
        data class Buffer(val index: Int, val offset: Int, val size: Int, val flags: Int) : OutputEvent
    }

    private fun decodeAsync(extractor: MediaExtractor, format: MediaFormat, checkCancelled: () -> Unit): FloatArray {
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        val worker = HandlerThread("neuroscribe-decoder")
        val active = AtomicBoolean(true)
        val failure = AtomicReference<Throwable>()
        val inputGuard = Any()
        // Backpressure bounds pending output buffers. Callbacks preserve event order;
        // only the caller owns PCM state, and only the callback thread owns input reads.
        val events = ArrayBlockingQueue<OutputEvent>(8)
        var workerStarted = false
        fun enqueue(event: OutputEvent) {
            while (active.get()) if (events.offer(event, 10, TimeUnit.MILLISECONDS)) return
        }
        var inputEnded = false
        try {
            worker.start(); workerStarted = true
            decoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    synchronized(inputGuard) {
                        if (!active.get() || inputEnded) return
                        try {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        } catch (e: Throwable) { failure.compareAndSet(null, e) }
                    }
                }
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (!active.get()) return
                    try {
                        // BufferInfo belongs to the framework; copy its fields before returning.
                        enqueue(OutputEvent.Buffer(index, info.offset, info.size, info.flags))
                    } catch (e: Throwable) { failure.compareAndSet(null, e) }
                }
                override fun onOutputFormatChanged(codec: MediaCodec, out: MediaFormat) {
                    if (!active.get()) return
                    try {
                        enqueue(OutputEvent.Format(out.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            out.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT))
                    } catch (e: Throwable) { failure.compareAndSet(null, e) }
                }
                override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                    failure.compareAndSet(null, error)
                }
            }, Handler(worker.looper))
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var collector: PcmCollector? = null
            var lastOutput = System.nanoTime()
            while (true) {
                checkCancelled()
                failure.get()?.let { throw it }
                require(System.nanoTime() - lastOutput < 30_000_000_000L) { "Декодер аудио перестал отвечать." }
                when (val event = events.poll(50, TimeUnit.MILLISECONDS)) {
                    null -> continue
                    is OutputEvent.Format -> {
                        require(collector == null || event.rate == rate) { "Смена частоты внутри записи не поддерживается." }
                        rate = event.rate; channels = event.channels; encoding = event.encoding
                    }
                    is OutputEvent.Buffer -> {
                        lastOutput = System.nanoTime()
                        try {
                            if (event.size > 0) {
                                require(channels in 1..8 && rate in 8000..192000)
                                require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    "Неподдерживаемый PCM-формат декодера: $encoding"
                                }
                                val output = decoder.getOutputBuffer(event.index)!!.order(ByteOrder.LITTLE_ENDIAN)
                                output.position(event.offset); output.limit(event.offset + event.size)
                                val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val sink = collector ?: PcmCollector(rate).also { collector = it }
                                while (output.remaining() >= channels * bytes) {
                                    var sum = 0f
                                    repeat(channels) { sum += if (bytes == 4) output.float else output.short / 32768f }
                                    sink.add(sum / channels)
                                }
                            }
                        } finally { decoder.releaseOutputBuffer(event.index, false) }
                        if (event.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return collector?.finish() ?: error("Аудиозапись пуста.")
        } finally {
            // Unblock a callback waiting on a full queue, then wait for any current
            // input read/queue operation before invalidating the codec's buffers.
            // Future input callbacks observe active=false while holding the same guard.
            active.set(false)
            synchronized(inputGuard) { }
            try {
                decoder.runCatching { stop() }
                decoder.release()
            } finally {
                if (workerStarted) {
                    worker.quitSafely()
                    var interrupted = false
                    while (worker.isAlive) {
                        try { worker.join() } catch (_: InterruptedException) { interrupted = true }
                    }
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
    }
}
