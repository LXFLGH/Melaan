package com.melavonmen.an

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melavonmen.an.data.ReaderOverrides
import com.melavonmen.an.data.ReaderSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderSettingsStoreTest {
    @Test
    fun perComicOverridesPersistAndCanFallBackToGlobal() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("melaan_settings", 0)
        val id = "test:${UUID.randomUUID()}"
        try {
            val saved = ReaderOverrides(scrollMode = true, rtl = false, fitWidth = true, doublePage = true)
            ReaderSettingsStore.save(prefs, id, saved)
            assertEquals(saved, ReaderSettingsStore.load(prefs, id))
            ReaderSettingsStore.save(prefs, id, ReaderOverrides())
            assertEquals(ReaderOverrides(), ReaderSettingsStore.load(prefs, id))
        } finally {
            ReaderSettingsStore.save(prefs, id, ReaderOverrides())
        }
    }
}
