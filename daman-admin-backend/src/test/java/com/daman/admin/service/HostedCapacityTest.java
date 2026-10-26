package com.daman.admin.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HostedCapacityTest {
    @Test void allocatesLowestFreePortFrom8091() {
        assertEquals(8091, HostedCapacity.nextFreePort(List.of(8083, 8090)));
        assertEquals(8092, HostedCapacity.nextFreePort(List.of(8083, 8090, 8091)));
        assertEquals(8091, HostedCapacity.nextFreePort(List.of(8083, 8090, 8092)));
    }

    /** The real box: 3814 MB total, a 768 MB heap per store, 1024 MB reserved, a 300 MB margin. */
    private static boolean roomOn3814With(long stores) {
        return HostedCapacity.hasRoomForAnotherStore(3814, stores, 768, 1024, 300);
    }

    @Test void reservesEachExistingStoresWholeHeapNotItsCurrentUsage() {
        // Spec §7's conclusion, reproduced by the guard: this box holds roughly three stores.
        assertTrue(roomOn3814With(0), "an empty box has room for the first store");
        assertTrue(roomOn3814With(1), "with viora alone there is room for a second");
        assertTrue(roomOn3814With(2), "and for a third");
        assertFalse(roomOn3814With(3), "but not a fourth");
        assertFalse(roomOn3814With(9), "and certainly not a tenth");
    }

    @Test void theReserveAndTheMarginAreBothRespected() {
        // Exactly on the boundary: 2092 - 1024 = 1068, and the new store needs 768 + 300 = 1068.
        assertTrue(HostedCapacity.hasRoomForAnotherStore(2092, 0, 768, 1024, 300));
        assertFalse(HostedCapacity.hasRoomForAnotherStore(2091, 0, 768, 1024, 300));
        // A margin of 0 still refuses when the heap itself does not fit.
        assertTrue(HostedCapacity.hasRoomForAnotherStore(1792, 0, 768, 1024, 0));
        assertFalse(HostedCapacity.hasRoomForAnotherStore(1791, 0, 768, 1024, 0));
    }

    @Test void aSecondStoreCannotSlipThroughWhileTheFirstHeapIsStillYoung() {
        // The bug this replaced: MemAvailable reflects a freshly booted store's small RSS, so two
        // back-to-back provisions could both pass. Here the input is the registry's own count, so
        // the second request sees the first store's FULL heap however young its JVM is.
        long total = 2600; // room for exactly one store: 2600 - 1024 = 1576 >= 1068
        assertTrue(HostedCapacity.hasRoomForAnotherStore(total, 0, 768, 1024, 300));
        assertFalse(HostedCapacity.hasRoomForAnotherStore(total, 1, 768, 1024, 300));
    }

    @Test void maxStoresOnTheRealBoxIsThree() {
        assertEquals(3, HostedCapacity.maxStores(3814, 768,
                HostedCapacity.NON_STORE_RESERVE_MB, HostedCapacity.RAM_MARGIN_MB));
    }

    @Test void maxStoresNeverGoesNegative() {
        assertEquals(0, HostedCapacity.maxStores(500, 768, 1024, 300));
    }

    @Test void theGuardAndMaxStoresAgreeForEveryCountOnEveryBoxSize() {
        // One definition of capacity: another store fits exactly while existing < maxStores.
        for (long total = 0; total <= 8192; total += 97) {
            int max = HostedCapacity.maxStores(total, 768, 1024, 300);
            for (long existing = 0; existing <= 12; existing++) {
                assertEquals(existing < max,
                        HostedCapacity.hasRoomForAnotherStore(total, existing, 768, 1024, 300),
                        "total=" + total + " existing=" + existing);
            }
        }
    }
}
