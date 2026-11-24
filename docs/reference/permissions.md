# Permissions history

Which permissions were removed in PR-G and which were added later; the exact current set is pinned by `MergedManifestTest`.

**Permissions (PR-G, history):** `RECORD_AUDIO`, `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_PHONE_CALL`
were removed: the P2 ring needs no foreground service and P2 had no audio. (The answer path later
brought back the microphone ones, and ["Stay connected"](../user-guide/stay-connected.md) added `FOREGROUND_SERVICE_CONNECTED_DEVICE` and
`CHANGE_NETWORK_STATE`; `MergedManifestTest` pins the current exact set.) `MergedManifestTest`
asserts they are absent and that `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`,
`USE_FULL_SCREEN_INTENT`, `RECEIVE_BOOT_COMPLETED`, and the library-merged `WAKE_LOCK` and
`c2dm.permission.RECEIVE` are present.
