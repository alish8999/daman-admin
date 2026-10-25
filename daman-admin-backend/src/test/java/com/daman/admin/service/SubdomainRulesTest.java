package com.daman.admin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubdomainRulesTest {
    @Test void sanitisesRealClientCodes() {
        assertEquals("abu-ammar", SubdomainRules.sanitise("abu_ammar"));
        assertEquals("bistro-cafe", SubdomainRules.sanitise("Bistro-cafe"));
        assertEquals("alhasan-pharmacy", SubdomainRules.sanitise("alhasan-pharmacy"));
    }

    @Test void rejectsUnderscoreAndEdgeHyphens() {
        assertFalse(SubdomainRules.isValid("abu_ammar"));
        assertFalse(SubdomainRules.isValid("-shop"));
        assertFalse(SubdomainRules.isValid("shop-"));
        assertFalse(SubdomainRules.isValid("Shop"));
        assertTrue(SubdomainRules.isValid("alhasan"));
    }

    @Test void refusesHostnamesThatServeLiveInfrastructure() {
        for (String r : new String[]{"admin", "admin-api", "dl", "www", "viora"}) {
            assertTrue(SubdomainRules.isReserved(r), r + " must be reserved");
        }
        assertFalse(SubdomainRules.isReserved("alhasan"));
    }

    @Test void reservesTheApiSiblingShape() {
        assertTrue(SubdomainRules.isReserved("something-api"));
    }

    @Test void truncatesOverLongCodesToAValidLabel() {
        String out = SubdomainRules.sanitise("a".repeat(40));
        assertEquals(32, out.length());
        assertTrue(SubdomainRules.isValid(out));
    }

    @Test void truncationNeverLeavesATrailingHyphen() {
        String out = SubdomainRules.sanitise("a".repeat(31) + "-bcd");
        assertEquals("a".repeat(31), out);
        assertTrue(SubdomainRules.isValid(out));
    }

    @Test void returnsEmptyWhenNothingUsableRemains() {
        assertEquals("", SubdomainRules.sanitise("متجر"));
        assertEquals("", SubdomainRules.sanitise("___"));
        assertEquals("", SubdomainRules.sanitise(null));
        assertFalse(SubdomainRules.isValid(SubdomainRules.sanitise("___")));
    }
}
