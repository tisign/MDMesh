package com.mdmesh.core.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.ProtocolJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.kioskDataStore: DataStore<Preferences> by preferencesDataStore(name = "mdm_kiosk")

/**
 * Persists the last-applied [KioskApplyPayload] so the agent can re-enter kiosk on boot
 * (the agent never receives the Headwind config — only commands — so there is nothing else
 * to re-read). [save]`(null)` clears it on `kiosk.exit`.
 */
interface KioskStateStore {
    /** Also drops a kiosk waiting for Android's setup ([savePendingSetup]): it has been entered, or cancelled. */
    suspend fun save(payload: KioskApplyPayload?)
    suspend fun load(): KioskApplyPayload?

    /**
     * A kiosk asked for while Android's setup wizard was still running, to be entered once it has finished (null
     * clears it). Kept apart from [save]: the device is not in this kiosk, so nothing that reads the kiosk state
     * (the launcher, the status screen, what the server is told) may see it. On disk, so it outlives the reboot a
     * setup can end with.
     */
    suspend fun savePendingSetup(payload: KioskApplyPayload?)
    suspend fun loadPendingSetup(): KioskApplyPayload?

    /**
     * Cold stream of the current kiosk payload (or `null` when not in kiosk), emitting on every
     * change. The launcher collects this so a background `kiosk.enter`/`kiosk.exit` command — which
     * runs in the check-in service, not the foreground activity — is reflected on screen
     * immediately (enter shows the grid/pinned app; exit drops to idle and unpins).
     */
    fun flow(): Flow<KioskApplyPayload?>
}

class DataStoreKioskStateStore(private val context: Context) : KioskStateStore {

    override suspend fun save(payload: KioskApplyPayload?) {
        context.kioskDataStore.edit {
            // In the same write: never a moment with a kiosk both entered and still waiting.
            it.remove(PENDING_SETUP)
            if (payload == null) {
                it.remove(KEY)
            } else {
                it[KEY] = ProtocolJson.json.encodeToString(KioskApplyPayload.serializer(), payload)
            }
        }
    }

    override suspend fun savePendingSetup(payload: KioskApplyPayload?) {
        context.kioskDataStore.edit {
            if (payload == null) {
                it.remove(PENDING_SETUP)
            } else {
                it[PENDING_SETUP] = ProtocolJson.json.encodeToString(KioskApplyPayload.serializer(), payload)
            }
        }
    }

    override suspend fun loadPendingSetup(): KioskApplyPayload? =
        context.kioskDataStore.data.map { it[PENDING_SETUP] }.first()?.let(::decode)

    override suspend fun load(): KioskApplyPayload? {
        val raw = context.kioskDataStore.data.map { it[KEY] }.first() ?: return null
        return decode(raw)
    }

    override fun flow(): Flow<KioskApplyPayload?> =
        context.kioskDataStore.data.map { prefs -> prefs[KEY]?.let(::decode) }

    private fun decode(raw: String): KioskApplyPayload? = runCatching {
        ProtocolJson.json.decodeFromString(KioskApplyPayload.serializer(), raw)
    }.getOrNull()

    private companion object {
        val KEY = stringPreferencesKey("kiosk_payload")
        val PENDING_SETUP = stringPreferencesKey("setup_pending_kiosk")
    }
}

/** In-memory [KioskStateStore] for unit tests. */
class InMemoryKioskStateStore(initial: KioskApplyPayload? = null) : KioskStateStore {
    private val state = MutableStateFlow(initial)

    /** As saved: a StateFlow keeps the old value for an equal new one (an image's new URL, same SHA-256). */
    private var latest = initial

    private var pendingSetup: KioskApplyPayload? = null

    override suspend fun save(payload: KioskApplyPayload?) {
        pendingSetup = null
        latest = payload
        state.value = payload
    }

    override suspend fun savePendingSetup(payload: KioskApplyPayload?) {
        pendingSetup = payload
    }

    override suspend fun loadPendingSetup(): KioskApplyPayload? = pendingSetup

    override suspend fun load(): KioskApplyPayload? = latest

    override fun flow(): Flow<KioskApplyPayload?> = state
}
