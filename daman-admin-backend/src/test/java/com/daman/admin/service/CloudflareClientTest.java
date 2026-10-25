package com.daman.admin.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CloudflareClientTest {

    private static final String DUMMY_TOKEN = "dummy-token-not-real-123";

    private final List<String> calls = new ArrayList<>();

    private CloudflareClient clientReturning(String envelope) {
        return new CloudflareClient(new ObjectMapper(), "acct1", "zone1", (method, path, body) -> {
            calls.add(method + " " + path + (body == null ? "" : " " + body));
            return envelope;
        });
    }

    @Test
    void createProxiedCnameReturnsRecordId() {
        CloudflareClient c = clientReturning("{\"success\":true,\"result\":{\"id\":\"rec123\"}}");
        assertEquals("rec123", c.createProxiedCname("shop", "daman-store.pages.dev"));
        assertTrue(calls.get(0).startsWith("POST /zones/zone1/dns_records "));
        assertTrue(calls.get(0).contains("\"proxied\":true"));
        assertTrue(calls.get(0).contains("daman-store.pages.dev"));
    }

    @Test
    void failedEnvelopeThrowsWithCloudflareMessage() {
        CloudflareClient c = clientReturning("{\"success\":false,\"errors\":[{\"code\":1004,\"message\":\"bad\"}]}");
        CloudflareClient.CloudflareException e = assertThrows(CloudflareClient.CloudflareException.class,
                () -> c.deleteDnsRecord("rec1"));
        assertTrue(e.getMessage().contains("1004"));
        assertTrue(e.getMessage().contains("bad"));
    }

    @Test
    void pagesDomainStatusReadsResultStatus() {
        CloudflareClient c = clientReturning("{\"success\":true,\"result\":{\"status\":\"active\"}}");
        assertEquals("active", c.pagesDomainStatus("daman-store", "shop.example.com"));
        assertEquals("GET /accounts/acct1/pages/projects/daman-store/domains/shop.example.com", calls.get(0));
    }

    @Test
    void findDnsRecordIdIsEmptyWhenNoRecords() {
        CloudflareClient c = clientReturning("{\"success\":true,\"result\":[]}");
        assertEquals(Optional.empty(), c.findDnsRecordId("shop.example.com"));
    }

    @Test
    void findDnsRecordIdReturnsFirstRecord() {
        CloudflareClient c = clientReturning("{\"success\":true,\"result\":[{\"id\":\"r9\"}]}");
        assertEquals(Optional.of("r9"), c.findDnsRecordId("shop.example.com"));
        assertEquals("GET /zones/zone1/dns_records?name=shop.example.com", calls.get(0));
    }

    @Test
    void attachAndDetachPagesDomainUseExpectedEndpoints() {
        CloudflareClient c = clientReturning("{\"success\":true,\"result\":{}}");
        c.attachPagesDomain("daman-store", "shop.example.com");
        c.detachPagesDomain("daman-store", "shop.example.com");
        assertEquals("POST /accounts/acct1/pages/projects/daman-store/domains {\"name\":\"shop.example.com\"}", calls.get(0));
        assertEquals("DELETE /accounts/acct1/pages/projects/daman-store/domains/shop.example.com", calls.get(1));
    }

    @Test
    void tokenIsNeverIncludedInAnyExceptionMessage() {
        CloudflareClient c = new CloudflareClient(new ObjectMapper(), "acct1", "zone1", (m, p, b) -> {
            throw new IllegalStateException("connect failed, Authorization: Bearer " + DUMMY_TOKEN);
        });
        CloudflareClient.CloudflareException e = assertThrows(CloudflareClient.CloudflareException.class,
                () -> c.findDnsRecordId("shop.example.com"));
        assertFalse(e.getMessage().contains(DUMMY_TOKEN));
        assertNull(e.getCause(), "cause must not be chained: its message/stack could carry the token");
    }

    @Test
    void unparseableResponseDoesNotEchoBody() {
        CloudflareClient c = clientReturning("<html>" + DUMMY_TOKEN + "</html>");
        CloudflareClient.CloudflareException e = assertThrows(CloudflareClient.CloudflareException.class,
                () -> c.findDnsRecordId("shop.example.com"));
        assertFalse(e.getMessage().contains(DUMMY_TOKEN));
    }
}
