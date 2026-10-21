package com.daman.admin.service;

/**
 * A client cannot be deleted because money has actually been received from them
 * (HTTP 409 {@code client_has_payments}).
 *
 * <p>Deleting a client cascades to its licences, trials, builds and billing rows, which is
 * right for clearing out dead trials but destroys the record of a real commercial
 * relationship. A paying client is revoked, not deleted.
 */
public class ClientHasPaymentsException extends RuntimeException {
    public ClientHasPaymentsException(String clientCode, long paidCount) {
        super("Client " + clientCode + " has " + paidCount + " recorded payment(s) and cannot be deleted");
    }
}
