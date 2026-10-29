package com.devoid.keysync.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The top status prompt is the one overlay element the user can switch off from
 * the notification bar. It is persisted, unlike [DataStoreManager]'s other
 * visibility toggle, so "hidden" has to survive a service restart -- otherwise
 * the prompt would come back every time the overlay is relaunched.
 */
class StatusBarSettingTest {

    private class Store(initial: Preferences) : DataStore<Preferences> {
        var value = initial
        override val data: Flow<Preferences> get() = flow { emit(value) }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            value = transform(value)
            return value
        }
    }

    private fun manager(store: DataStore<Preferences>) = DataStoreManager(store) { _, _ -> }

    @Test
    fun nothingStoredMeansTheCallerFallsBackToVisible() = runBlocking {
        val manager = manager(Store(emptyPreferences()))
        assertNull(manager.getBoolean(DataStoreManager.STATUS_BAR_VISIBLE).first())
    }

    @Test
    fun hidingThePromptSurvivesARestart() = runBlocking {
        val store = Store(emptyPreferences())
        manager(store).save(DataStoreManager.STATUS_BAR_VISIBLE, false)

        // A second manager over the same file stands in for the service restarting.
        val afterRestart = manager(store).getBoolean(DataStoreManager.STATUS_BAR_VISIBLE).first()
        assertEquals(false, afterRestart)
    }

    @Test
    fun showingItAgainOverwritesTheStoredValue() = runBlocking {
        val store = Store(emptyPreferences())
        val manager = manager(store)
        manager.save(DataStoreManager.STATUS_BAR_VISIBLE, false)
        manager.save(DataStoreManager.STATUS_BAR_VISIBLE, true)

        assertEquals(true, manager.getBoolean(DataStoreManager.STATUS_BAR_VISIBLE).first())
    }

    /** Renaming the key would silently reset every user's choice, so pin it. */
    @Test
    fun theKeyNameIsStable() {
        assertEquals("status_bar_visible", DataStoreManager.STATUS_BAR_VISIBLE.name)
    }
}
