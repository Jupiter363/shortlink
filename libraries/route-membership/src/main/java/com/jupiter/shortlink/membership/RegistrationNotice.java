package com.jupiter.shortlink.membership;

/** A wake-up hint only. Consumers obtain authoritative entries and leases from the primary. */
public record RegistrationNotice(String namespace, String generation, long revision, long memberCount) { }
