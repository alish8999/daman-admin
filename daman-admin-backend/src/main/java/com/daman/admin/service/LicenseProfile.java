package com.daman.admin.service;

/**
 * Business profile carried inside a v2 licence payload so a shop's identity survives a
 * database reset. Every field is optional: legacy {@code ClientConfig} rows have nulls, and
 * {@code LicenseKeyService} omits blank fields (and the whole object if all are blank).
 */
public record LicenseProfile(String appName, String phone, String address,
                             String storeType, Boolean hasTables) {}
