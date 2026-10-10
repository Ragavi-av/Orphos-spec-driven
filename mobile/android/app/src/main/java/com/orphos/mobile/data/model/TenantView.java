package com.orphos.mobile.data.model;

/** GET /api/public/tenants/by-slug/{slug} (PublicTenantView). Minimal by design. */
public class TenantView {
    public String id;             // maps to tenantId client-side
    public String domain;
    public String keycloakRealm;  // e.g. tenant-{uuid}
    public String status;         // ACTIVE | SUSPENDED | DELETED
}
