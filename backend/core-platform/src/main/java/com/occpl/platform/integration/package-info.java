/**
 * Service API for product services (client-credentials tokens): access checks, usage reporting and
 * provisioning callbacks. The calling product is always derived from the token's client, never from
 * the request, so a product can only act on its own entitlements and jobs.
 */
package com.occpl.platform.integration;
