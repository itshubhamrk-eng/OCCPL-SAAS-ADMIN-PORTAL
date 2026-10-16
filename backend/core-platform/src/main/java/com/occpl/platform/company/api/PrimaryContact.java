package com.occpl.platform.company.api;

/** The company's primary contact: who a product should create as the company's first administrator. */
public record PrimaryContact(String name, String email, String phone) {
}
