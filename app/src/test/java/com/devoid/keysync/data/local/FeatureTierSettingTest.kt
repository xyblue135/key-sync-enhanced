package com.devoid.keysync.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.devoid.keysync.model.FeatureTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 功能模式是持久化的全局设置。读不出的值必须回落成完整模式，
 * 否则升级后会静默停用老用户已经配置好的宏。
 */
class FeatureTierSettingTest {

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
    fun nothingStoredMeansFullMode() {
        assertEquals(FeatureTier.FULL, FeatureTier.fromStorage(null))
    }

    @Test
    fun unknownValueFallsBackToFullMode() {
        assertEquals(FeatureTier.FULL, FeatureTier.fromStorage(""))
        assertEquals(FeatureTier.FULL, FeatureTier.fromStorage("experimental"))
    }

    @Test
    fun basicModeSurvivesARestart() = runBlocking {
        val store = Store(emptyPreferences())
        val manager = manager(store)
        assertNull(manager.getString(DataStoreManager.FEATURE_TIER).first())

        manager.save(DataStoreManager.FEATURE_TIER, FeatureTier.BASIC.storageValue)

        // A second manager over the same file stands in for the service restarting.
        val afterRestart = manager(store).getString(DataStoreManager.FEATURE_TIER).first()
        assertEquals(FeatureTier.BASIC, FeatureTier.fromStorage(afterRestart))
    }

    /** Renaming the key would silently reset every user's choice, so pin it. */
    @Test
    fun theKeyNameIsStable() {
        assertEquals("feature_tier", DataStoreManager.FEATURE_TIER.name)
    }
}
