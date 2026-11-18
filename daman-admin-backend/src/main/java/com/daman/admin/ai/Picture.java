package com.daman.admin.ai;

/**
 * Image bytes and the type they are, travelling together.
 *
 * <p>Separate from the service that draws it because the fitter hands one back too, and a
 * picture that has been re-encoded is the same kind of thing as one that has not.
 */
public record Picture(byte[] bytes, String contentType) {}
