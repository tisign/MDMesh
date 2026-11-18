package com.mdmesh.core.kiosk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** The Android side effects of entering/leaving kiosk, behind an interface so the orchestration is unit-testable. */
interface KioskHomeSwitch {
    /** Enable/disable the agent's HOME activity-alias (the persistent-preferred HOME while in kiosk). */
    fun setClaimEnabled(enabled: Boolean)
    /** Bring the kiosk launcher forward (best-effort; the HOME claim covers the next HOME press). */
    fun showLauncher()
    /** Send the device to the OEM launcher after kiosk exit. */
    fun showOemHome()
}

class AndroidKioskHomeSwitch(private val context: Context, private val homeComponent: ComponentName) : KioskHomeSwitch {
    override fun setClaimEnabled(enabled: Boolean) {
        runCatching {
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            context.packageManager.setComponentEnabledSetting(homeComponent, state, PackageManager.DONT_KILL_APP)
        }
    }
    /**
     * As a HOME intent, never by component. Android gives a component-explicit start from an app the standard
     * activity type, even for a HOME activity, so the launcher it creates is not the device's home task: the next
     * real HOME start (the Home key, or Android going home when a task ends) then creates a second launcher on top
     * of the locked one. Without a package when HOME already resolves to this alias (the kiosk's persistent
     * preferred HOME, set just before): that is the intent a Home press sends, so the same launcher is reused.
     * Otherwise within our own package, so it can never open another launcher or a chooser.
     */
    override fun showLauncher() {
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            val ours = resolved?.packageName == homeComponent.packageName && resolved.name == homeComponent.className
            if (!ours) home.setPackage(homeComponent.packageName)
            context.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    override fun showOemHome() {
        runCatching { context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
