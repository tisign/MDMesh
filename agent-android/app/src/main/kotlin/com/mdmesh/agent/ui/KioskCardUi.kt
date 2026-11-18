package com.mdmesh.agent.ui

import androidx.annotation.StringRes
import com.mdmesh.agent.R

/**
 * What the status screen's Kiosk card shows, and whether its button is the way (back) into a kiosk.
 *
 * While a kiosk is saved (the agent holds the device in one) the button always goes back to it ([resumes]), locked or
 * not: this screen showing means the person is not on the kiosk's own screens, and lock task dropping out from under
 * a saved kiosk is exactly when they need a way back. Before, the button hid whenever lock task was on, which left
 * them here with none. With no kiosk saved, Return to kiosk is offered as before: to a Device Owner that isn't locked
 * and has a configured kiosk. Either way it is disabled with "Returning…" while it runs.
 */
data class KioskCardUi(
    @StringRes val pill: Int,
    val active: Boolean,
    @StringRes val body: Int,
    val returnVisible: Boolean,
    val returnEnabled: Boolean,
    @StringRes val returnLabel: Int,
    /** A return is running. */
    val returning: Boolean,
    /**
     * The button goes back to the kiosk that is saved, rather than entering the configured one. Nothing new is locked
     * by it, so it asks nothing first.
     */
    val resumes: Boolean = false,
) {
    companion object {
        /** [saved]: the agent has a kiosk saved, i.e. it believes the device is in one. */
        fun of(
            deviceOwner: Boolean,
            locked: Boolean,
            configured: Boolean,
            returning: Boolean,
            saved: Boolean = false,
        ): KioskCardUi {
            val resumes = deviceOwner && saved
            val canReturn = resumes || (deviceOwner && !locked && configured)
            return KioskCardUi(
                pill = if (locked) R.string.status_kiosk_on else R.string.status_kiosk_off,
                active = locked,
                body = body(locked, saved, configured),
                returnVisible = canReturn,
                returnEnabled = canReturn && !returning,
                returnLabel = when {
                    returning -> R.string.kiosk_return_busy
                    resumes && locked -> R.string.kiosk_resume_action
                    else -> R.string.kiosk_return_action
                },
                returning = returning,
                resumes = resumes,
            )
        }

        @StringRes
        private fun body(locked: Boolean, saved: Boolean, configured: Boolean): Int = when {
            locked -> R.string.status_kiosk_body_locked
            // The agent holds a kiosk but lock task is off: said plainly, the button below locks it again.
            saved -> R.string.status_kiosk_body_unlocked
            configured -> R.string.status_kiosk_body_ready
            else -> R.string.status_kiosk_body_off
        }
    }
}
