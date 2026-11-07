package ru.neuroscribe.app.core

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONArray

class SpeakerClusteringTest {
    @Test fun realEmbeddingsDoNotSplitSingleVoicesOrMergeFourSpeakers() {
        val data=JSONArray(javaClass.getResource("/speaker-embeddings.json")!!.readText())
        for (i in 0 until data.length()) {
            val row=data.getJSONObject(i);val array=row.getJSONArray("vectors");val time=row.getJSONArray("seconds")
            val vectors=(0 until array.length()).map { j -> val v=array.getJSONArray(j);FloatArray(v.length()) { v.getDouble(it).toFloat() } }
            val labels=SpeakerClustering.cluster(vectors,DoubleArray(time.length()) { time.getDouble(it) })
            assertEquals(row.getString("name"),row.getInt("expected"),labels.toSet().size)
        }
    }
    @Test fun headcountIsNotHardcodedAndRepeatedVoicesKeepIdentity() {
        for (count in 1..5) {
            val vectors=(0 until count).flatMap { k -> List(3) { FloatArray(5) { if(it==k) 1f else 0f } } }
            val labels=SpeakerClustering.cluster(vectors,DoubleArray(vectors.size) { 4.0 })
            assertEquals(count,labels.toSet().size)
            for (k in 0 until count) assertEquals(1,labels.slice(k*3 until k*3+3).toSet().size)
        }
    }
    @Test fun isolatedShortOutlierDoesNotEstablishAnotherSpeaker() {
        val vectors=List(10) { floatArrayOf(1f,0f,0f) } + List(10) { floatArrayOf(0f,1f,0f) } + listOf(floatArrayOf(0f,0f,1f))
        val weights=DoubleArray(21) { if(it==20) 1.6 else 4.0 }
        assertEquals(2,SpeakerClustering.cluster(vectors,weights).toSet().size)
    }
    @Test fun silenceAndInvalidVectorsDoNotProduceFakeClusters() {
        assertTrue(SpeakerClustering.cluster(emptyList(),doubleArrayOf()).isEmpty())
        assertNull(SpeakerClustering.normalize(floatArrayOf(0f,0f)))
        assertNull(SpeakerClustering.normalize(floatArrayOf(Float.NaN,1f)))
    }
    @Test fun clusteringChecksCancellation() {
        var cancelled=false
        try { SpeakerClustering.cluster(List(6) { floatArrayOf(1f,0f) },DoubleArray(6) { 2.0 }) { throw InterruptedException() } }
        catch (_:InterruptedException) { cancelled=true }
        assertTrue(cancelled)
    }
}
