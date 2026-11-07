package ru.neuroscribe.app.core

import org.junit.Assert.*
import org.junit.Test

class PunctuationPrefixGuardTest {
    @Test fun everyBytePrefixOfValidPunctuationRemainsAllowed() {
        for ((raw, edited) in listOf(
            "добрый день как дела" to "Добрый день! Как дела?",
            "price 12 50 and 3" to "Price: 12,50 and -3%.",
            "ΟΣ ΑΒΓ" to "ΟΣ, ΑΒΓ!",
            "ΟΣ’Α" to "ΟΣ’Α.",
            "hello 世界 café" to "Hello, 世界 — café!")) {
            val guard = PunctuationPrefixGuard(raw)
            val bytes = edited.toByteArray()
            for (n in 0..bytes.size) assertTrue("$edited byte $n", guard.mayContinue(bytes.copyOf(n)))
        }
    }
    @Test fun completedChangedOrExtraWordsCannotBeRepairedByAppending() {
        val guard = PunctuationPrefixGuard("Добрый день")
        assertTrue(guard.mayContinue("Добрый де".toByteArray()))
        for (bad in listOf("Hello ", "Добрый вечер, ", "Добрый день сегодня ")) {
            assertFalse(bad, guard.mayContinue(bad.toByteArray()))
            for (suffix in listOf("", "день.", " Добрый день!")) {
                assertEquals("Добрый день", Transcript.safePunctuation("Добрый день", bad + suffix))
            }
        }
    }
}
