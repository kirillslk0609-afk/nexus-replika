package ru.neuroscribe.app

import androidx.test.platform.app.InstrumentationRegistry
import android.provider.Settings
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import ru.neuroscribe.app.engine.ModelStore
import java.io.File

class ModelPreparationTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun allModelsCanBePreparedFromApkInAirplaneMode() {
        assertEquals(1,Settings.Global.getInt(context.contentResolver,Settings.Global.AIRPLANE_MODE_ON,0))
        val directory=File(context.filesDir,"models")
        val backup=File(context.filesDir,"models-cold-test-backup")
        assertFalse("Previous cold test backup needs manual recovery",backup.exists())
        if (directory.exists()) assertTrue(directory.renameTo(backup))
        var success=false
        try {
            val start=System.nanoTime()
            ModelStore(context).prepare({}, {})
            assertEquals(6,directory.listFiles()!!.count { !it.name.endsWith(".part") })
            File(context.filesDir,"test-cold-prepare.json").writeText(JSONObject()
                .put("airplaneMode",true).put("modelFiles",6)
                .put("preparationMs",(System.nanoTime()-start)/1_000_000).toString(2))
            success=true
        } finally {
            if (success) backup.deleteRecursively()
            else { directory.deleteRecursively(); if(backup.exists()) check(backup.renameTo(directory)) }
        }
    }
    @Test fun corruptedSameLengthModelIsRepairedFromApk() {
        val store=ModelStore(context);store.prepare({}, {})
        val file=File(store.file("tokens.txt"));val original=file.readBytes()
        val corrupt=original.copyOf();corrupt[0]=(corrupt[0].toInt() xor 127).toByte()
        file.writeBytes(corrupt);assertTrue(file.setLastModified(System.currentTimeMillis()+5000))
        try { store.prepare({}, {});assertArrayEquals(original,file.readBytes()) }
        finally { if(!file.readBytes().contentEquals(original)) file.writeBytes(original) }
    }
}
