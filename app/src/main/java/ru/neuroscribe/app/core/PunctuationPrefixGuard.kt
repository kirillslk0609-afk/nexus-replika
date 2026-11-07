package ru.neuroscribe.app.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Locale

/** Reject only a completed word that the final lexical guard must reject too. */
class PunctuationPrefixGuard(source: String) {
    private val words = Regex("[\\p{L}\\p{N}]+")
    private val expected = words.findAll(source.lowercase(Locale.ROOT)).map { it.value }.toList()

    fun mayContinue(bytes: ByteArray): Boolean {
        val prefix = try {
            // A token can end inside a UTF-8 character. Wait until it is complete.
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { return true }
        // Only commit text before an ASCII whitespace barrier. Case-ignorable
        // punctuation can change Greek final-sigma casing when more letters arrive.
        val boundary = prefix.indexOfLast { it == ' ' || it == '\n' || it == '\r' || it == '\t' }
        if (boundary < 0) return true
        val matches = words.findAll(prefix.substring(0, boundary + 1).lowercase(Locale.ROOT))
        for ((index, match) in matches.withIndex()) {
            if (index >= expected.size || match.value != expected[index]) return false
        }
        return true
    }
}
