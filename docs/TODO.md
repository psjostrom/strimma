# TODO

## Dual-storage atomicity in SettingsRepository

`setStartOnBoot()` writes to DataStore and SharedPreferences sequentially. If the process crashes between the two writes, the storages become inconsistent — the user could disable start-on-boot in the UI (DataStore updated) but BootReceiver still reads the old value from SharedPreferences and starts the service anyway.

The same pattern exists in `setGlucoseSource()`. Both should write SharedPreferences inside the `dataStore.edit {}` block so the sync write only happens if DataStore commits successfully.
