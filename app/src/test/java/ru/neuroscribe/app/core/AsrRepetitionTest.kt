package ru.neuroscribe.app.core

import org.junit.Assert.*
import org.junit.Test

class AsrRepetitionTest {
    @Test fun loopsTriggerButOrdinaryRepeatedWordsDoNot() {
        assertTrue(AsrRepetition.isLoop(List(19) { "Вон" }.joinToString(", ")))
        assertTrue(AsrRepetition.isLoop(List(5) { "Thank you everyone" }.joinToString(". ")))
        assertFalse(AsrRepetition.isLoop("Да, да, да, я согласен. Мы проверим запись и сохраним результат."))
        assertFalse(AsrRepetition.isLoop("We will test the application today and we will review the results tomorrow."))
        assertFalse(AsrRepetition.isLoop(""))
    }

    @Test fun retryMustActuallyResolveTheLoop() {
        val loop = List(12) { "Вон" }.joinToString(" ")
        assertEquals("Поезд прибывает на второй путь.", AsrRepetition.choose(loop, "Поезд прибывает на второй путь."))
        assertEquals(loop, AsrRepetition.choose(loop, ""))
        assertEquals(loop, AsrRepetition.choose(loop, List(10) { "Да" }.joinToString(" ")))
        assertEquals("Да, да, да.", AsrRepetition.choose("Да, да, да.", "Нет."))
    }
}
