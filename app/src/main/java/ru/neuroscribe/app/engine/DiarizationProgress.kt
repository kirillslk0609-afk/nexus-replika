package ru.neuroscribe.app.engine

// sherpa-onnx JNI looks up invoke(IIJ)Ljava/lang/Integer; by its concrete signature.
// Kotlin invokedynamic lambdas expose only the erased Function3 method. A named
// implementation preserves the typed method as well as the generic bridge.
internal class DiarizationProgress(private val progress: (String, Int) -> Unit) : (Int, Int, Long) -> Int {
    override fun invoke(done: Int, total: Int, argument: Long): Int {
        // Cancellation is checked between native stages, never by throwing through JNI.
        progress("Определяем говорящих: $done/$total", 8 + 25 * done / total.coerceAtLeast(1))
        return 0
    }
}
