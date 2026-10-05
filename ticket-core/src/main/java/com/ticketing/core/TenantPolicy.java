package com.ticketing.core;

/**
 * Per-tenant capacity policy (from the tenant registry): requests per minute and concurrent requests
 * the tenant may use, and its tier (standard, business, enterprise).
 */
public record TenantPolicy(String tenantId, String tier, int requestsPerMinute, int maxConcurrent) {
}
