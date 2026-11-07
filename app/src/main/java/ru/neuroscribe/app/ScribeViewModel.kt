package ru.neuroscribe.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import ru.neuroscribe.app.core.Transcript
import ru.neuroscribe.app.core.Turn
import ru.neuroscribe.app.engine.TranscriptionEngine
import java.io.File

data class ScreenState(
    val fileName: String = "", val busy: Boolean = false, val cancelling: Boolean = false,
    val stage: String = "", val progress: Int = 0, val error: String? = null,
    val turns: List<Turn> = emptyList(), val finished: Boolean = false,
    val durationMs: Long = 0, val processingMs: Long = 0, val llmCalls: Int = 0,
    val rejectedEdits: Int = 0, val overlap: Boolean = false
)

class ScribeViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private val audio = File(application.filesDir, "current-audio")
    private val saved = File(application.filesDir, "last-result.json")
    private var task: Job? = null
    val audioFile: File get() = audio

    init {
        // Restore only complete results, written atomically after successful processing.
        runCatching {
            if (saved.exists() && audio.exists()) {
                val o = JSONObject(saved.readText())
                mutable.value = ScreenState(fileName = o.getString("fileName"), turns = Transcript.fromJson(o.getJSONObject("result").toString()),
                    finished = true, stage = "Готово", durationMs = o.getLong("durationMs"), processingMs = o.getLong("processingMs"),
                    llmCalls = o.getInt("llmCalls"), rejectedEdits = o.getInt("rejectedEdits"), overlap = o.getBoolean("overlap"))
            }
        }.onFailure { saved.delete() }
    }

    fun importAudio(uri: Uri) {
        if (mutable.value.busy) return
        val previous = mutable.value
        task = viewModelScope.launch {
            mutable.value = ScreenState(busy = true, stage = "Копируем выбранную запись")
            val part = File(audio.parentFile, "audio.part")
            try {
                val name = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "Аудиозапись"
                    resolver.openInputStream(uri)?.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(1024 * 128)
                            var total = 0L
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 256L * 1024 * 1024) { "Максимальный размер файла — 256 МБ." }
                                output.write(buffer, 0, count)
                            }
                            require(total > 0) { "Выбранный файл пуст." }
                        }
                    } ?: error("Не удалось открыть файл.")
                    ensureActive()
                    saved.delete()
                    check(part.renameTo(audio)) { "Не удалось сохранить аудио." }
                    name
                }
                mutable.value = ScreenState(fileName = name)
            } catch (e: CancellationException) {
                mutable.value = previous.copy(stage = "Импорт отменён — предыдущая запись сохранена", busy = false)
            } catch (e: Exception) {
                mutable.value = previous.copy(error = e.message ?: "Не удалось импортировать запись.", busy = false)
            } finally {
                part.delete()
                mutable.update { it.copy(busy = false, cancelling = false) }
            }
        }
    }

    fun transcribe(language: String) {
        if (mutable.value.busy || mutable.value.fileName.isBlank() || !audio.exists()) return
        task = viewModelScope.launch {
            mutable.update { it.copy(busy = true, cancelling = false, error = null, finished = false, turns = emptyList(), progress = 0, stage = "Подготовка") }
            saved.delete()
            try {
                val result = withContext(Dispatchers.Default) {
                    val job = currentCoroutineContext().job
                    TranscriptionEngine(getApplication()).process(audio, language,
                        { stage, progress -> mutable.update { it.copy(stage = stage, progress = progress) } },
                        { job.ensureActive() })
                }
                val complete = mutable.value.copy(busy = false, progress = 100, stage = "Готово", finished = true,
                    turns = result.turns, durationMs = result.durationMs, processingMs = result.processingMs,
                    llmCalls = result.llmCalls, rejectedEdits = result.rejectedEdits, overlap = result.overlap)
                withContext(Dispatchers.IO) {
                    val json = JSONObject().put("fileName", complete.fileName)
                        .put("result", JSONObject(Transcript.toJson(complete.turns)))
                        .put("durationMs", complete.durationMs).put("processingMs", complete.processingMs)
                        .put("llmCalls", complete.llmCalls).put("rejectedEdits", complete.rejectedEdits).put("overlap", complete.overlap)
                    val part = File(saved.parentFile, "result.part")
                    part.writeText(json.toString())
                    check(part.renameTo(saved)) { "Не удалось сохранить результат." }
                }
                mutable.value = complete
            } catch (e: CancellationException) {
                mutable.update { it.copy(stage = "Обработка отменена", progress = 0, turns = emptyList(), finished = false) }
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: "Ошибка обработки.", stage = "Не удалось обработать запись", progress = 0) }
            } catch (e: UnsatisfiedLinkError) {
                mutable.update { it.copy(error = "Нативная библиотека недоступна. Нужен Android с поддержкой arm64-v8a.", progress = 0) }
            } finally { mutable.update { it.copy(busy = false, cancelling = false) } }
        }
    }

    fun cancel() {
        if (mutable.value.busy) {
            mutable.update { it.copy(cancelling = true) }
            task?.cancel()
        }
    }

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val snapshot = mutable.value
        check(snapshot.finished)
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(Transcript.toJson(snapshot.turns))
        } ?: error("Не удалось открыть файл для записи.")
    }
}
