package com.mdmesh.agent

import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mdmesh.agent.ui.DialogSlot
import com.mdmesh.agent.ui.ExitPinDialog
import com.mdmesh.agent.ui.ExitPinUiState
import com.mdmesh.agent.ui.MdmDialog
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.sync.CheckInWorker
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.kiosk.ExitPinFormat
import com.mdmesh.kiosk.ExitPinGate
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.sameKioskAs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The exit PIN prompt, opened by a kiosk app: `startActivity` with action [ACTION]. In a single-app kiosk the
 * launcher's own exit (its button or seven-tap corner) sits behind the pinned app, so the app offers its own hidden
 * way in and hands over to this prompt.
 *
 * The request authorizes nothing. Nothing in the intent is read; the prompt is the launcher's: the same PIN
 * ([KioskExitDialogs.onDevicePin], so none for a kiosk whose exit is console-only), the same [ExitPinGate] with its
 * count of wrong PINs and its lockout, checked against the kiosk on the device when the PIN is submitted, not the
 * one the prompt opened for. With no kiosk, or none that can be left on the device, it closes at once and the asking
 * app is back in front.
 *
 * It is its own activity, not the launcher brought forward: HOME coming to the front in a single-app kiosk means the
 * app closed, which the launcher counts towards its crash-loop hold and answers by opening the app again, over the
 * prompt. Cancel (or Back, a minute without typing, the screen going off) closes this one and leaves the app where it
 * was.
 *
 * The exit is the one a console Exit kiosk makes ([KioskApplier.exit], through its journal), bound to the kiosk the
 * PIN was checked against. Not forced, unlike the launcher's: only the launcher can take its own task out of lock
 * task, so an exit Android refuses changes nothing here, and the prompt says so, instead of leaving a device that is
 * still locked with its kiosk state cleared.
 */
// Five lifecycle callbacks, and one function per dialog and step of the PIN check, as in the launcher.
@Suppress("TooManyFunctions")
@AndroidEntryPoint
class KioskExitActivity : ComponentActivity() {

    @Inject lateinit var store: KioskStateStore
    @Inject lateinit var applier: KioskApplier
    @Inject lateinit var events: EventSink
    @Inject lateinit var pinGate: ExitPinGate

    /** The kiosk on the device, as last delivered: every PIN is checked against this one. */
    private var kiosk: KioskApplyPayload? = null

    /** A dialog that closes by itself (Cancel, Back, idle, OK) takes the activity with it. */
    private val dialogs = DialogSlot("exitDialog") { if (!exiting) finish() }

    /** The right PIN was entered and the exit is running: the kiosk state changing under it is its own doing. */
    private var exiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dialogs.restore(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { store.flow().collect(::onKiosk) }
        }
    }

    /** The kiosk as it is now, and again whenever it changes while the prompt is open (the console, a new PIN). */
    private fun onKiosk(current: KioskApplyPayload?) {
        val previous = kiosk
        kiosk = current
        when {
            exiting -> Unit
            // No kiosk, or none with an exit on the device: nothing to ask for. Also when it ends meanwhile.
            KioskExitDialogs.onDevicePin(current) == null -> leave()
            // Another kiosk took its place: the prompt was not opened for that one.
            previous != null && !previous.sameKioskAs(current) -> leave()
            dialogs.showing != null -> Unit
            // What was open before a recreation comes back; nothing typed is ever restored.
            dialogs.takeRestored() == KioskExitDialogs.UNAVAILABLE -> showExitUnavailable()
            else -> promptExit(current)
        }
    }

    private fun leave() {
        dialogs.dismiss()
        finish()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dialogs.onConfigurationChanged(newConfig) { tag, typed ->
            if (tag == KioskExitDialogs.UNAVAILABLE) showExitUnavailable() else promptExit(kiosk, typed)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        dialogs.save(outState)
    }

    /** Off the screen (the screen went off, another app came up): the prompt goes, with whatever was typed. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !exiting) leave()
    }

    override fun onDestroy() {
        dialogs.dismiss()
        super.onDestroy()
    }

    private fun promptExit(current: KioskApplyPayload?, typed: CharSequence? = null) {
        val pin = KioskExitDialogs.onDevicePin(current) ?: return leave()
        pinGate.remainingLockMs(pin)?.let { return showExitLocked(it) }
        val prompt = ExitPinDialog.create(this, typed, ExitPinFormat.numeric(pin), ::onPinEntered)
        pinGate.attemptsLeft(pin)?.let { prompt.render(ExitPinUiState.triesLeft(it)) }
        dialogs.show(KioskExitDialogs.PIN, prompt.dialog)
    }

    /** Checked against the kiosk on the device now: a PIN changed or removed meanwhile must not still open the exit. */
    private fun onPinEntered(entered: String, dialog: ExitPinDialog) {
        val current = kiosk
        val outcome = pinGate.attempt(entered, KioskExitDialogs.onDevicePin(current))
        // Before the prompt closes itself on Open: its closing must not take the activity from under the exit.
        if (outcome == ExitPinGate.Outcome.Open && current != null) exiting = true
        dialog.render(ExitPinUiState.of(outcome))
        when (outcome) {
            ExitPinGate.Outcome.Open -> exit(current ?: return)
            is ExitPinGate.Outcome.Locked -> {
                if (outcome.started) reportLockout(outcome)
                showExitLocked(outcome.remainingMs)
            }
            is ExitPinGate.Outcome.Wrong, ExitPinGate.Outcome.Unavailable -> Unit
        }
    }

    /** Tell the server now, as the launcher does: administrators hear about it while it is happening. */
    private fun reportLockout(lockout: ExitPinGate.Outcome.Locked) {
        events.record(
            "kioskPinLockout",
            "${lockout.failures} wrong exit PINs; exit locked for ${ExitPinGate.describe(lockout.remainingMs)}",
        )
        CheckInWorker.scheduleNow(applicationContext)
    }

    /** The launcher's "Exit locked", counting down; it closes when the lockout ends or after the prompt's idle time. */
    private fun showExitLocked(remainingMs: Long) {
        fun message(ms: Long) = getString(R.string.kiosk_exit_locked_message, ExitPinGate.describe(ms))
        val spec = MdmDialog.Spec(
            title = getString(R.string.kiosk_exit_locked_title),
            message = message(remainingMs),
            confirm = getString(R.string.action_ok),
            icon = R.drawable.ic_v2_lock,
        )
        val dialog = MdmDialog.create(this, spec) { it.dismiss() }
        val closeAt = SystemClock.elapsedRealtime() + ExitPinDialog.idleTimeoutMs(this)
        dialog.everyWhileShown(LOCKED_TICK_MS) {
            val left = pinGate.remainingLockMs(KioskExitDialogs.onDevicePin(kiosk))
            if (left == null || SystemClock.elapsedRealtime() >= closeAt) {
                dialog.dismiss()
            } else {
                dialog.updateMessage(message(left))
            }
        }
        dialogs.show(KioskExitDialogs.LOCKED, dialog)
    }

    /** The right PIN, but the device is still in its kiosk: the exit was refused. */
    private fun showExitUnavailable() {
        val spec = MdmDialog.Spec(
            title = getString(R.string.kiosk_exit_title),
            message = getString(R.string.kiosk_exit_unavailable),
            confirm = getString(R.string.action_ok),
            icon = R.drawable.ic_v2_warning,
        )
        val dialog = MdmDialog.create(this, spec) { it.dismiss() }
        dialog.everyWhileShown(ExitPinDialog.idleTimeoutMs(this)) { dialog.dismiss() }
        dialogs.show(KioskExitDialogs.UNAVAILABLE, dialog)
    }

    /**
     * Exits [authorized], the kiosk the PIN was checked against, and only it. NonCancellable: dropping the allowlist
     * makes Android finish this activity (it is one of the locked tasks), and the exit must still run to its end, as
     * must its record and the check-in. A process death in between is settled at the next start (ExitJournal).
     */
    private fun exit(authorized: KioskApplyPayload) {
        lifecycleScope.launch {
            val result = withContext(NonCancellable) {
                val result = applier.exit(handBackHome = true, force = false, expected = authorized)
                if (result == KioskResult.Ok) {
                    runCatching { events.record("kioskExit", EXIT_EVENT) }
                        .onFailure { Log.w(TAG, "recording the kiosk exit failed", it) }
                    // The device has left its kiosk: say so without waiting for the periodic check-in.
                    CheckInWorker.scheduleAfterCurrent(applicationContext)
                }
                result
            }
            when (result) {
                KioskResult.Ok -> finish()
                // Another kiosk was entered first: the PIN was not for that one, and nothing was changed.
                KioskResult.Failed(KioskApplier.EXIT_SUPERSEDED) -> finish()
                else -> {
                    exiting = false
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) showExitUnavailable() else finish()
                }
            }
        }
    }

    companion object {
        /** The action a kiosk app starts this activity with. Part of the contract with those apps: never renamed. */
        const val ACTION = "com.mdmesh.action.REQUEST_KIOSK_EXIT"

        /** The `kioskExit` event's detail: told apart from the launcher's "exited on-device". */
        const val EXIT_EVENT = "exited on-device from app"

        private const val TAG = "KioskExit"

        /** How often the "Exit locked" countdown is redrawn (its finest unit is a second). */
        private const val LOCKED_TICK_MS = 1_000L
    }
}
