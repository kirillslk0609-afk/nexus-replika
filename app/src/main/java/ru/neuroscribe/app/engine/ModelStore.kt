package ru.neuroscribe.app.engine

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ModelStore(private val context: Context) {
    companion object {
        // Private app files: rehash on every new process or metadata/manifest change.
        private val verified = ConcurrentHashMap<String, String>()
    }
    private val directory = File(context.filesDir, "models")
    private val filenames = listOf("encoder.onnx", "decoder.onnx", "tokens.txt", "segmentation.onnx", "embedding.onnx", "punctuation.gguf")

    fun file(name: String): String = File(directory, name).absolutePath

    fun prepare(progress: (String) -> Unit, checkCancelled: () -> Unit) {
        directory.mkdirs()
        val manifest = try {
            JSONObject(context.assets.open("models/manifest.json").bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            throw IllegalStateException("В APK нет пакета моделей. Соберите полную версию по инструкции README.", e)
        }
        filenames.forEachIndexed { i, name ->
            checkCancelled()
            progress("Проверка моделей ${i + 1}/${filenames.size}")
            val metadata = manifest.getJSONObject(name)
            val target = File(directory, name)
            val expected = metadata.getString("sha256")
            fun fingerprint() = "$expected:${target.length()}:${target.lastModified()}"
            if (target.exists() && target.length() == metadata.getLong("bytes")) {
                if (verified[target.absolutePath] == fingerprint()) return@forEachIndexed
                if (digest(target, checkCancelled) == expected) {
                    verified[target.absolutePath] = fingerprint()
                    return@forEachIndexed
                }
            }
            progress("Подготовка моделей ${i + 1}/${filenames.size}. Первый запуск…")
            val partial = File(directory, "$name.part")
            try {
                context.assets.open("models/$name").use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(digest(partial, checkCancelled) == expected) { "Повреждена модель $name." }
                check(partial.renameTo(target)) { "Не удалось сохранить модель $name. Проверьте свободное место." }
                verified[target.absolutePath] = fingerprint()
            } finally { partial.delete() }
        }
    }

    private fun digest(file: File, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
