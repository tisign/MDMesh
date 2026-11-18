package com.mdmesh.agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mdmesh.agent.service.CheckInService
import com.mdmesh.agent.ui.DialogSlot
import com.mdmesh.agent.ui.MdmDialog
import com.mdmesh.agent.ui.StatusScreen
import com.mdmesh.core.action.NotificationPermissionAccess
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MDMesh agent status screen. It never enters lock task by itself: the kiosk launcher is the root locked task,
 * also when staff return to the kiosk from here (see the manifest).
 *
 * Shows the real managed state — Device-Owner status, server-issued device id, kiosk state,
 * and the server URL — so a person looking at the device can see it's managed by MDMesh.
 * The state lives in [StatusViewModel] (one [com.mdmesh.agent.ui.StatusUiState]); [StatusScreen] draws it.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var notificationPermission: NotificationPermissionAccess

    private val model: StatusViewModel by viewModels()
    /** The Return to kiosk confirmation; reopened after a rotation. */
    private val dialogs = DialogSlot("dialog")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)
        val screen = StatusScreen(this, onReturn = ::returnTapped, onCopy = model::copy)
        screen.render(model.state.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect(screen::render)
            }
        }
        // Grant our own POST_NOTIFICATIONS as Device Owner BEFORE starting the foreground
        // service, so the "MDMesh active" notification is visible on Android 13+.
        notificationPermission.ensure()
        // NOTE: we intentionally do NOT pop the battery-optimization request dialog here. As a
        // foreground-service Device Owner we survive Doze well enough, and that full-screen system
        // dialog used to sit on top and pause the kiosk launcher, so a kiosk.enter wouldn't take
        // visible effect until the user dismissed it. There is no public Device-Owner API to grant
        // the Doze exemption silently, so the safest behaviour is to not interrupt.
        // Start the near-real-time command channel (foreground poll loop). Starting from the
        // launcher activity keeps us in the foreground-start allowance on Android 12+.
        ContextCompat.startForegroundService(this, Intent(this, CheckInService::class.java))
        // Reopen the confirmation a rotation closed, if returning is still possible (the button says so).
        dialogs.restore(savedInstanceState)
        // Only ever the question again, never the action: a kiosk that came on meanwhile is not gone back to unasked.
        val kiosk = model.state.value.kiosk
        if (dialogs.takeRestored() == DIALOG_RETURN && kiosk.returnEnabled && !kiosk.resumes) confirmReturnToKiosk()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        dialogs.save(outState)
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen (not rotating): an old return failure no longer applies when the person comes back.
        if (!isChangingConfigurations) model.screenLeft()
    }

    override fun onDestroy() {
        dialogs.dismiss()
        super.onDestroy()
    }

    /** The Kiosk card's button. */
    private fun returnTapped() {
        val kiosk = model.state.value.kiosk
        if (kiosk.returning || dialogs.showing != null) return
        // A kiosk is saved: straight back to it. It locks nothing the device isn't already held in, so it asks nothing.
        if (kiosk.resumes) model.backToKiosk() else confirmReturnToKiosk()
    }

    /** Ask first: returning locks the device, and some kiosks have no exit on the device at all. */
    private fun confirmReturnToKiosk() {
        val kiosk = model.configuredKiosk ?: return model.refresh()
        val prompt = KioskReturnPrompt.of(kiosk)
        val spec = MdmDialog.Spec(
            title = getString(prompt.title),
            message = getString(prompt.message),
            confirm = getString(prompt.confirm),
            cancel = getString(R.string.action_cancel),
            // No exit on the device: an alert-tone lock in the header and a danger confirm.
            icon = if (prompt.danger) R.drawable.ic_v2_lock else 0,
            danger = prompt.danger,
        )
        val dialog = MdmDialog.create(this, spec) {
            it.dismiss()
            model.returnToKiosk(kiosk)
        }
        dialogs.show(DIALOG_RETURN, dialog)
    }

    private companion object {
        const val DIALOG_RETURN = "return"
    }
}
