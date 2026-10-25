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

    @Test void refusesWhenRamWouldNotLeaveTheMargin() {
        assertTrue(HostedCapacity.hasRoomFor(2214, 768, 300));
        assertFalse(HostedCapacity.hasRoomFor(900, 768, 300));
        assertFalse(HostedCapacity.hasRoomFor(768, 768, 300));
    }
}
