package com.daman.admin.service;

import java.util.Collection;

/** Pure port-allocation and RAM-guard helpers for hosted stores. */
public final class HostedCapacity {

    /** 8083 is the admin backend and 8090 is viora, so the band starts above them. */
    public static final int PORT_BAND_START = 8091;

    /** Free RAM that must remain after another store's heap (spec §7's suggestion). */
    public static final int RAM_MARGIN_MB = 300;

    /**
     * RAM reserved for everything on this box that is <b>not</b> a hosted store: the admin
     * backend's own JVM, plus the OS, sshd and cloudflared. See HostedStoreProvisioningService for
     * how 1024 was chosen (it allows a 2nd and a 3rd store and refuses a 4th on the 3814 MB box).
     */
    public static final int NON_STORE_RESERVE_MB = 1024;

    /**
     * The one registry status whose row is <b>not</b> holding a heap on this box. Declared here,
     * beside the arithmetic that depends on it, rather than imported from
     * {@code HostedStoreLifecycleService} — this class is a pure helper with no service deps.
     */
    private static final String SUSPENDED = "SUSPENDED";

    private HostedCapacity() {}

    /**
     * Whether a registry row in this status still ties up a store's heap on this box.
     *
     * <p>Everything does <b>except</b> {@code SUSPENDED}, and that one exception is the whole
     * point. The guard reserves each row's full {@code -Xmx} rather than measuring current usage,
     * on the reasoning that {@code Restart=always} brings a store's JVM back whether or not it is
     * up at this instant. That reasoning was written (and was true) before suspend existed. It is
     * false for exactly one status: {@code HostedStoreLifecycleService.suspend} runs
     * {@code disable --now}, so the unit is stopped <i>and disabled</i> and neither a reboot nor a
     * release walk revives it. Counting a suspended store reserves memory nothing can claim, and
     * an operator who suspends a store to free room is then refused by the very act that freed it.
     *
     * <p>{@code FAILED} deliberately still counts: provisioning broke partway, so the unit may
     * already have been written and enabled, and nothing has disabled it. Same for an unrecognised
     * or null status — the conservative reading is the safe one everywhere the premise holds.
     */
    public static boolean holdsHeap(String status) {
        return !SUSPENDED.equals(status);
    }

    /**
     * How many of these registry rows this box is committed to the heap of — the figure both the
     * provisioning guard and the dashboard's capacity strip count, so they cannot disagree about
     * whether the box is full.
     */
    public static long committedStores(Collection<String> statuses) {
        return statuses.stream().filter(HostedCapacity::holdsHeap).count();
    }

    /**
     * The most stores this box can commit to: the largest N for which the Nth store still passes
     * {@link #hasRoomForAnotherStore}. This is THE definition of capacity: the guard that refuses
     * provisioning and the number the dashboard shows are both this, so they cannot disagree.
     */
    public static int maxStores(long totalMb, int heapMb, int reserveMb, int marginMb) {
        long fits = (totalMb - reserveMb - marginMb) / heapMb;
        return (int) Math.max(0L, fits);
    }

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
     * @param heapMb         one store's whole expected footprint, not just its heap
     *                       ({@link StoreUnitRenderer#MEMORY_BUDGET_MB} — the same number the
     *                       unit holds it to with {@code MemoryHigh})
     * @param reserveMb      RAM reserved for everything that is not a store (admin backend, OS)
     * @param marginMb       free RAM that must still remain after the new store's heap
     */
    public static boolean hasRoomForAnotherStore(long totalMb, long existingStores, int heapMb,
                                                 int reserveMb, int marginMb) {
        return existingStores < maxStores(totalMb, heapMb, reserveMb, marginMb);
    }
}
