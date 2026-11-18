package com.mdmesh.core.provisioning

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Android's own "setup finished" flags, which a kiosk waits for on a device just enrolled by QR code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceSetupStateTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val setup get() = DeviceSetupState(app)

    private fun provisioned(value: Int) =
        Settings.Global.putInt(app.contentResolver, Settings.Global.DEVICE_PROVISIONED, value)

    private fun userSetUp(value: Int) = Settings.Secure.putInt(app.contentResolver, USER_SETUP_COMPLETE, value)

    @Test fun `setup is complete only when the device is provisioned and the user finished the wizard`() {
        assertFalse("a device in its setup wizard", setup.isComplete())
        provisioned(1)
        assertFalse("provisioned, the wizard still open (QR enrollment)", setup.isComplete())
        userSetUp(1)
        assertTrue(setup.isComplete())
        provisioned(0)
        assertFalse(setup.isComplete())
    }

    @Test fun `completion is told now and again when a flag changes`() = runTest(UnconfinedTestDispatcher()) {
        provisioned(1)
        val seen = mutableListOf<Boolean>()
        val watching = launch { setup.completion().take(2).toList(seen) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(false), seen)
        userSetUp(1)
        app.contentResolver.notifyChange(Settings.Secure.getUriFor(USER_SETUP_COMPLETE), null)
        shadowOf(Looper.getMainLooper()).idle()
        watching.join()
        assertEquals(listOf(false, true), seen)
        assertTrue(setup.completion().first())
    }

    private companion object {
        const val USER_SETUP_COMPLETE = "user_setup_complete"
    }
}
