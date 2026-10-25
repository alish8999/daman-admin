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

    public static boolean hasRoomFor(long availableMb, int heapMb, int marginMb) {
        return availableMb - heapMb >= marginMb;
    }
}
