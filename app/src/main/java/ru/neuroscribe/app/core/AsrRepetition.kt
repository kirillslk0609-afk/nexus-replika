package ru.neuroscribe.app.core

import java.util.Locale

/** Detect decoding loops; never delete or manufacture words in a hypothesis. */
object AsrRepetition {
    fun isLoop(text: String): Boolean {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (words.size < 8) return false
        val largest = words.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        if (largest >= 8 && largest.toDouble() / words.size >= 0.65) return true
        if (words.size < 12) return false
        val triples = words.windowed(3).groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        return triples >= 4 && triples * 3.0 / words.size >= 0.7
    }

    fun choose(original: String, retry: String): String =
        if (isLoop(original) && retry.isNotBlank() && !isLoop(retry)) retry else original
}
