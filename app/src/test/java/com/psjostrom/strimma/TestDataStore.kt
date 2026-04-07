package com.psjostrom.strimma

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Creates an isolated DataStore backed by a temp file.
 *
 * Pass [scope] from a `runTest` TestScope so DataStore operations run on the test dispatcher
 * (avoids hangs from Dispatchers.IO inside runTest's virtual time).
 */
fun createTestDataStore(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
): DataStore<Preferences> {
    val file = File.createTempFile("test_settings", ".preferences_pb")
    file.deleteOnExit()
    return PreferenceDataStoreFactory.create(scope = scope) { file }
}
