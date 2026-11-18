package com.mdmesh.agent.provisioning

import android.content.Context
import android.util.Log
import com.mdmesh.core.config.ConfigApplier
import com.mdmesh.core.provisioning.DeviceSetupState
import com.mdmesh.core.sync.CheckInWorker
import com.mdmesh.kiosk.KioskResult
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the kiosk that waited for Android's setup wizard as soon as the wizard has finished, without waiting for the
 * server to send it again: offline too, and after a restart of the agent or the device, since both what waits and
 * this watch start with the process. Once per process: setup that is complete stays complete.
 */
@Singleton
class SetupKioskResume @Inject constructor(
    private val setup: DeviceSetupState,
    /** Lazy: on a device long set up, the applier's graph isn't built at process start just to find nothing waiting. */
    private val applier: Lazy<ConfigApplier>,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            runCatching {
                // Suspends until setup is complete; the settings observer is released with it.
                setup.completion().first { it }
                resume()
            }.onFailure { Log.w(TAG, "Could not start the kiosk that waited for Android setup", it) }
        }
    }

    private suspend fun resume() {
        when (val entered = applier.get().resumeKioskAfterSetup()) {
            null -> Unit
            // Tell the server now: the kiosk is on, and its configuration is applied.
            KioskResult.Ok -> CheckInWorker.scheduleNow(context)
            // Still waiting on disk: the server's next config.apply, or the next start, tries again.
            else -> Log.w(TAG, "The kiosk that waited for Android setup did not start: $entered")
        }
    }

    private companion object {
        const val TAG = "SetupKioskResume"
    }
}
