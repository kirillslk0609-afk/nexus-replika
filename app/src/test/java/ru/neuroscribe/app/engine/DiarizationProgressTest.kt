package ru.neuroscribe.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DiarizationProgressTest {
    @Test fun exposesTheConcreteMethodLookedUpByNativeDiarization() {
        var percent = -1
        val callback = DiarizationProgress { _, value -> percent = value }
        val method = callback.javaClass.getMethod("invoke", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        assertEquals(Integer::class.java, method.returnType)
        assertEquals(0, method.invoke(callback, 1, 2, 0L))
        assertEquals(20, percent)
    }
}
