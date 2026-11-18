package com.mdmesh.core.store

import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KioskStateStoreTest {

    @Test
    fun `save then load returns the payload`() = runBlocking {
        val store = InMemoryKioskStateStore()
        val p = KioskApplyPayload(mode = "single", pinPackage = "com.x")
        store.save(p)
        assertEquals(p, store.load())
    }

    @Test
    fun `save null clears`() = runBlocking {
        val store = InMemoryKioskStateStore(KioskApplyPayload())
        store.save(null)
        assertNull(store.load())
    }

    @Test
    fun `a kiosk waiting for setup is kept apart from the kiosk the device is in`() = runBlocking {
        val store = InMemoryKioskStateStore()
        val waiting = KioskApplyPayload(mode = "single", pinPackage = "com.x")
        store.savePendingSetup(waiting)
        assertEquals(waiting, store.loadPendingSetup())
        assertNull("the device is in no kiosk", store.load())
        assertNull("and nothing that follows the kiosk state sees one", store.flow().first())
        store.savePendingSetup(null)
        assertNull(store.loadPendingSetup())
    }

    @Test
    fun `saving the kiosk state drops the one that waited, entered or cancelled`() = runBlocking {
        val store = InMemoryKioskStateStore()
        val waiting = KioskApplyPayload(mode = "single", pinPackage = "com.x")
        store.savePendingSetup(waiting)
        store.save(waiting)
        assertNull(store.loadPendingSetup())
        store.savePendingSetup(waiting)
        store.save(null)
        assertNull(store.loadPendingSetup())
    }
}
