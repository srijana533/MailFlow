package com.mailflow.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Tells Hibernate which organization the current session belongs to. Entities with
 * a {@code @TenantId} field are then filtered to that organization in every query,
 * and new rows get it automatically.
 *
 * <p>Registered by class name through {@code hibernate.tenant_identifier_resolver} in
 * application.yml, so it also applies to test slices without Spring component scanning.
 *
 * <p>Without a signed-in user the session is the {@link TenantContext#ROOT} tenant:
 * no filtering, and rows must be given their organization explicitly. Hibernate opens
 * the request's session after the security filter has authenticated the user (open
 * session in view), so a signed-in request always gets a filtered session.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<Long> {

    @Override
    public Long resolveCurrentTenantIdentifier() {
        return TenantContext.currentOrganizationId().orElse(TenantContext.ROOT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(Long tenantId) {
        return TenantContext.ROOT.equals(tenantId);
    }
}
