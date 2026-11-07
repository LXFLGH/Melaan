package com.melavonmen.an.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackupTest {
    @Test fun includesPerComicReaderOverridesButExcludesDirectoryGrants() {
        assertTrue(LocalBackup.isBackupSettingKey("reader.comic-1.rtl"))
        assertTrue(LocalBackup.isBackupSettingKey("reader.auto_next_chapter"))
        assertFalse(LocalBackup.isBackupSettingKey("tree_uri"))
        assertFalse(LocalBackup.isBackupSettingKey("app_background_uri"))
    }

    @Test fun importsApprovedAliasesWithoutOverwritingConflictingLocalGroups() {
        val merged = LocalBackup.mergeAliasGroups(
            mapOf("Local" to listOf("local variant")),
            mapOf("Backup" to listOf("backup variant"), "Other" to listOf("local variant"))
        )
        assertEquals(listOf("local variant"), merged["Local"])
        assertEquals(listOf("backup variant"), merged["Backup"])
        assertFalse("Other" in merged)
    }
}
