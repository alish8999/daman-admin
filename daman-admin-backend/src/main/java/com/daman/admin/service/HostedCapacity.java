package com.daman.admin.service;

import java.util.Collection;

/** Pure port-allocation and RAM-guard helpers for hosted stores. */
public final class HostedCapacity {

    /** 8083 is the admin backend and 8090 is viora, so the band starts above them. */
    public static final int PORT_BAND_START = 8091;

    private HostedCapacity() {}

    public static int nextFreePort(Collection<Integer> taken) {
        int port = PORT_BAND_START;
        while (taken.contains(port)) port++;
        return port;
    }

    /**
     * Whether one more store fits, by <b>reserving</b> what is already committed rather than
     * measuring what is currently used:
     *
     * <pre>totalMb - (heapMb * existingStores) - reserveMb &gt;= heapMb + marginMb</pre>
     *
     * <p>The earlier form took {@code MemAvailable} as its input, which reflects each store's
     * current RSS — and a freshly booted JVM sits well below its {@code -Xmx} ceiling for a while,
     * so two stores provisioned back to back could both pass before either heap matured. This form
     * depends on nothing that changes with timing.
     *
     * @param totalMb        total RAM on the box
     * @param existingStores stores already committed to a heap (the registry's own count)
     * @param heapMb         one store's {@code -Xmx} ({@link StoreUnitRenderer#HEAP_MB})
     * @param reserveMb      RAM reserved for everything that is not a store (admin backend, OS)
     * @param marginMb       free RAM that must still remain after the new store's heap
     */
    public static boolean hasRoomForAnotherStore(long totalMb, long existingStores, int heapMb,
                                                 int reserveMb, int marginMb) {
        long uncommitted = totalMb - (heapMb * existingStores) - reserveMb;
        return uncommitted >= (long) heapMb + marginMb;
    }
}
