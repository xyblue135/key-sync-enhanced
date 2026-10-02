package com.devoid.keysync.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.devoid.keysync.model.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DataStoreManagerRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    private val original = """[{"id":"original","name":"My layout"}]"""
    private val replacement = listOf(Profile("temporary", "Temporary"))

    private class Store(initial: Preferences) : DataStore<Preferences> {
        var value = initial
        var readError: Throwable? = null
        var writeError: Throwable? = null
        var writes = 0
        override val data: Flow<Preferences> get() = flow {
            readError?.let { throw it }
            emit(value)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writeError?.let { throw it }
            value = transform(value)
            writes++
            return value
        }
    }

    private fun manager(store: DataStore<Preferences>) = DataStoreManager(store) { _, _ -> }

    private suspend fun assertWriteBlocked(manager: DataStoreManager) {
        try {
            manager.saveProfiles(replacement)
            fail("A temporary profile must not replace unread data")
        } catch (_: IllegalStateException) { }
        try {
            manager.saveActiveProfileId("temporary")
            fail("The original active id must also be preserved")
        } catch (_: IllegalStateException) { }
    }

    @Test fun writesBeforeFirstReadAreRejected() = runBlocking {
        val store = Store(preferencesOf(DataStoreManager.PROFILES to original))
        assertWriteBlocked(manager(store))
        assertEquals(original, store.value[DataStoreManager.PROFILES])
        assertEquals(0, store.writes)
    }

    @Test fun temporaryIoFailureDoesNotBecomeFirstRunWhenWritesRecover() = runBlocking {
        val store = Store(preferencesOf(DataStoreManager.PROFILES to original,
            DataStoreManager.ACTIVE_PROFILE_ID to "original"))
        store.readError = IOException("temporary read failure")
        val manager = manager(store)
        val result = manager.getProfilesLoad().first() as ProfilesLoad.Unreadable
        assertNull(result.raw)
        store.readError = null
        assertWriteBlocked(manager)
        assertEquals(original, store.value[DataStoreManager.PROFILES])
        assertEquals("original", store.value[DataStoreManager.ACTIVE_PROFILE_ID])
        assertEquals(0, store.writes)
    }

    @Test fun corruptJsonIsBackedUpWithoutAllowingAutomaticReseed() = runBlocking {
        val raw = "not valid profile json"
        val store = Store(preferencesOf(DataStoreManager.PROFILES to raw))
        val manager = manager(store)
        val result = manager.getProfilesLoad().first() as ProfilesLoad.Unreadable
        manager.quarantineUnreadableProfiles(result.raw!!)
        assertWriteBlocked(manager)
        assertEquals(raw, store.value[DataStoreManager.PROFILES])
        assertEquals(raw, store.value[DataStoreManager.PROFILES_QUARANTINE])
    }

    @Test fun failedBackupNeverPermitsLaterOverwrite() = runBlocking {
        val raw = "broken json"
        val store = Store(preferencesOf(DataStoreManager.PROFILES to raw))
        val manager = manager(store)
        manager.getProfilesLoad().first()
        store.writeError = IOException("disk full")
        try {
            manager.quarantineUnreadableProfiles(raw)
            fail("Expected failed backup")
        } catch (_: IOException) { }
        store.writeError = null
        assertWriteBlocked(manager)
        assertEquals(raw, store.value[DataStoreManager.PROFILES])
        assertNull(store.value[DataStoreManager.PROFILES_QUARANTINE])
    }

    @Test fun existingDifferentBackupIsPreserved() = runBlocking {
        val store = Store(preferencesOf(DataStoreManager.PROFILES to "new broken json",
            DataStoreManager.PROFILES_QUARANTINE to "older backup"))
        val manager = manager(store)
        manager.getProfilesLoad().first()
        try {
            manager.quarantineUnreadableProfiles("new broken json")
            fail("Must not replace an earlier backup")
        } catch (_: IllegalStateException) { }
        assertEquals("older backup", store.value[DataStoreManager.PROFILES_QUARANTINE])
        assertWriteBlocked(manager)
    }

    @Test fun backupRejectsStaleSnapshot() = runBlocking {
        val store = Store(preferencesOf(DataStoreManager.PROFILES to original))
        try {
            manager(store).quarantineUnreadableProfiles("stale broken json")
            fail("Must check the source before backing it up")
        } catch (_: IllegalStateException) { }
        assertEquals(original, store.value[DataStoreManager.PROFILES])
        assertNull(store.value[DataStoreManager.PROFILES_QUARANTINE])
    }

    @Test fun genuineFirstRunCanSave() = runBlocking {
        val store = Store(emptyPreferences())
        val manager = manager(store)
        assertEquals(ProfilesLoad.Absent, manager.getProfilesLoad().first())
        manager.saveProfiles(replacement)
        manager.saveActiveProfileId("temporary")
        assertEquals(replacement, (manager.getProfilesLoad().first() as ProfilesLoad.Loaded).profiles)
        assertEquals("temporary", store.value[DataStoreManager.ACTIVE_PROFILE_ID])
    }

    @Test fun successfulReloadRestoresWritesAfterIoFailure() = runBlocking {
        val store = Store(preferencesOf(DataStoreManager.PROFILES to original))
        val manager = manager(store)
        store.readError = IOException("offline")
        manager.getProfilesLoad().first()
        store.readError = null
        assertTrue(manager.getProfilesLoad().first() is ProfilesLoad.Loaded)
        manager.saveProfiles(replacement)
        assertEquals(replacement, (manager.getProfilesLoad().first() as ProfilesLoad.Loaded).profiles)
    }

    @Test fun cancellationIsNotConvertedIntoUnreadableData() = runBlocking {
        val store = Store(emptyPreferences())
        store.readError = CancellationException("cancelled")
        try {
            manager(store).getProfilesLoad().first()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(0, store.writes)
    }

    @Test fun corruptPreferencesFileRemainsByteForByteIntact() = runBlocking {
        val file = temp.newFile("corrupt.preferences_pb")
        val bytes = byteArrayOf(-1, -1, -1, -1)
        file.writeBytes(bytes)
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val manager = manager(store)
            assertTrue(manager.getProfilesLoad().first() is ProfilesLoad.Unreadable)
            assertWriteBlocked(manager)
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            scope.cancel()
            job.join()
        }
    }
}
