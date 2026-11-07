package ru.neuroscribe.app.core

import kotlin.math.max
import kotlin.math.sqrt

/** Cluster reliable speech tracks, choosing separation from this recording, not a supplied headcount. */
object SpeakerClustering {
    fun normalize(v: FloatArray): FloatArray? {
        if (v.isEmpty() || v.any { !it.isFinite() }) return null
        val norm = sqrt(v.sumOf { it.toDouble() * it })
        return if (norm < 1e-9) null else FloatArray(v.size) { (v[it] / norm).toFloat() }
    }
    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { a[it].toDouble() * b[it] }
    fun centers(vectors: List<FloatArray>, labels: IntArray): List<FloatArray> =
        (0..(labels.maxOrNull() ?: -1)).map { k ->
            val ids = labels.indices.filter { labels[it] == k }
            normalize(FloatArray(vectors.first().size) { d -> ids.sumOf { vectors[it][d].toDouble() }.toFloat() })!!
        }
    fun nearest(v: FloatArray, centers: List<FloatArray>): Int = centers.indices.maxBy { dot(v, centers[it]) }

    fun cluster(vectors: List<FloatArray>, seconds: DoubleArray, checkCancelled: () -> Unit = {}): IntArray {
        require(vectors.size == seconds.size && seconds.all { it > 0 && it.isFinite() })
        if (vectors.isEmpty()) return IntArray(0)
        val v = vectors.map { requireNotNull(normalize(it)) }
        require(v.all { it.size == v.first().size })
        val n = v.size
        val original = Array(n) { i -> DoubleArray(n) { j -> max(0.0, 1.0 - dot(v[i], v[j])) } }
        val distances = Array(n) { original[it].copyOf() }
        val members = Array(n) { mutableListOf(it) }
        val active = BooleanArray(n) { true }
        val totalWeight = seconds.sum()
        var bestScore = Double.NEGATIVE_INFINITY
        var best = IntArray(n)
        val seen = mutableSetOf<String>()
        for (step in 0..28) {
            val threshold = 0.25 + step * 0.025
            while (true) {
                checkCancelled()
                var left = -1; var right = -1; var distance = Double.POSITIVE_INFINITY
                for (i in 0 until n) if (active[i]) for (j in 0 until i) if (active[j] && distances[i][j] < distance) {
                    distance = distances[i][j]; left = i; right = j
                }
                if (left < 0 || distance > threshold) break
                val a = members[left].size; val b = members[right].size
                for (k in 0 until n) if (active[k] && k != left && k != right) {
                    val value = (distances[left][k] * a + distances[right][k] * b) / (a + b)
                    distances[right][k] = value; distances[k][right] = value
                }
                members[right].addAll(members[left]); active[left] = false
            }
            val all = members.indices.filter { active[it] }.map { members[it].toList() }
            // Tiny clusters do not establish new speakers. Their tracks are reassigned, never dropped.
            val minimum = max(2.5, totalWeight * 0.05)
            val reliable = all.filter { g -> g.sumOf { seconds[it] } >= minimum }
                .ifEmpty { listOf(all.maxBy { g -> g.sumOf { seconds[it] } }) }
            val key = reliable.joinToString(";") { it.sorted().joinToString(",") }
            if (!seen.add(key)) continue
            val centroids = reliable.map { group ->
                normalize(FloatArray(v.first().size) { d -> group.sumOf { v[it][d].toDouble() }.toFloat() })!!
            }
            val labels = IntArray(n) { nearest(v[it], centroids) }
            reliable.forEachIndexed { k, group -> group.forEach { labels[it] = k } }
            val groups = reliable.indices.map { k -> labels.indices.filter { labels[it] == k } }
            var score = if (groups.size == 1) {
                var dispersion = 0.0
                for (i in 0 until n) for (j in 0 until n) dispersion += original[i][j] * seconds[i] * seconds[j]
                if (dispersion / (totalWeight * totalWeight) < 0.25) 0.25 else 0.0
            } else {
                var sum = 0.0
                for (i in 0 until n) {
                    val own = groups[labels[i]].filter { it != i }
                    if (own.isEmpty()) continue
                    fun mean(group: List<Int>) = group.sumOf { original[i][it] * seconds[it] } / group.sumOf { seconds[it] }
                    val a = mean(own)
                    val b = groups.indices.filter { it != labels[i] }.minOf { mean(groups[it]) }
                    sum += (b - a) / max(max(a, b), 1e-9) * seconds[i]
                }
                sum / totalWeight
            }
            // Prefer the simpler explanation when fragmented clusters only marginally improve separation.
            score -= 0.05 * (groups.size - 1)
            if (score > bestScore) { bestScore = score; best = labels }
        }
        return best
    }
}
