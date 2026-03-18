package dev.digitalgnosis.dispatch.fcm

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for FCM idempotency guard in DispatchFcmService.
 * Verifies duplicate message detection works correctly.
 */
class FcmDedupTest {

    @Before
    fun setup() {
        // Reset the static dedup set between tests using reflection
        val field = DispatchFcmService::class.java.getDeclaredField("seenMessageIds")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val set = field.get(null) as LinkedHashSet<String>
        set.clear()
    }

    @Test
    fun `first message is not a duplicate`() {
        assertFalse(DispatchFcmService.isDuplicate("msg-001"))
    }

    @Test
    fun `same message ID is detected as duplicate`() {
        assertFalse(DispatchFcmService.isDuplicate("msg-002"))
        assertTrue(DispatchFcmService.isDuplicate("msg-002"))
    }

    @Test
    fun `different message IDs are not duplicates`() {
        assertFalse(DispatchFcmService.isDuplicate("msg-003"))
        assertFalse(DispatchFcmService.isDuplicate("msg-004"))
    }

    @Test
    fun `null message ID is never a duplicate`() {
        assertFalse(DispatchFcmService.isDuplicate(null))
        assertFalse(DispatchFcmService.isDuplicate(null))
    }

    @Test
    fun `blank message ID is never a duplicate`() {
        assertFalse(DispatchFcmService.isDuplicate(""))
        assertFalse(DispatchFcmService.isDuplicate(""))
    }

    @Test
    fun `eviction keeps set bounded`() {
        // Fill beyond capacity (200)
        for (i in 1..210) {
            assertFalse(DispatchFcmService.isDuplicate("fill-$i"))
        }

        // Recent entries are still tracked
        assertTrue(DispatchFcmService.isDuplicate("fill-210"))
        assertTrue(DispatchFcmService.isDuplicate("fill-200"))

        // Oldest entries have been evicted (set capped at 200)
        assertFalse(DispatchFcmService.isDuplicate("fill-1"))
    }
}
