package com.mdmesh.core.provisioning

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether Android's own setup has finished: the setup wizard a new or factory-reset device runs, which goes on after
 * QR provisioning has made the agent Device Owner and it has enrolled (the remaining steps are the manufacturer's).
 * Read only: the agent never marks setup complete and never disables the wizard.
 *
 * Complete means both of Android's flags are set: the device is provisioned and the user has finished setup. A flag
 * that can't be read counts as set: the kiosk then starts as it did before it waited for setup, rather than never.
 */
class DeviceSetupState(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun isComplete(): Boolean =
        isSet { Settings.Global.getInt(resolver, Settings.Global.DEVICE_PROVISIONED, 0) } &&
            isSet { Settings.Secure.getInt(resolver, USER_SETUP_COMPLETE, 0) }

    private inline fun isSet(read: () -> Int): Boolean = runCatching { read() == 1 }.getOrDefault(true)

    /** [isComplete] now, and again whenever one of the two flags changes. */
    fun completion(): Flow<Boolean> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(isComplete())
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED), false, observer)
        resolver.registerContentObserver(Settings.Secure.getUriFor(USER_SETUP_COMPLETE), false, observer)
        // Registered first, then read: a change in between is not missed.
        trySend(isComplete())
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    private companion object {
        /**
         * Settings.Secure.USER_SETUP_COMPLETE: marked readable for apps in AOSP, but the constant itself is not in
         * the public SDK.
         */
        const val USER_SETUP_COMPLETE = "user_setup_complete"
    }
}
