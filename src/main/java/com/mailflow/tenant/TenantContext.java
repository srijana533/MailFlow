package com.mailflow.tenant;

import com.mailflow.auth.entity.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * The organization (company workspace) of the signed-in user.
 *
 * <p>Web requests from a signed-in user are scoped to that user's organization.
 * Code without a signed-in user (the batch job, the RabbitMQ consumer, the scheduler,
 * public unsubscribe links) has no organization and runs as the {@link #ROOT} tenant:
 * Hibernate does not filter its queries, so that code must scope by the
 * organization of the campaign it works on.
 */
public final class TenantContext {

    /** Tenant value for code without a signed-in user. Never stored in a row. */
    public static final Long ROOT = -1L;

    private TenantContext() {}

    /** Organization of the signed-in user, or empty when nobody is signed in. */
    public static Optional<Long> currentOrganizationId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return Optional.ofNullable(user.getOrganizationId());
        }
        return Optional.empty();
    }

    /** Organization of the signed-in user; 403 when the request has none. */
    public static Long requireOrganizationId() {
        return currentOrganizationId()
                .orElseThrow(() -> new AccessDeniedException("No organization for the current user"));
    }

    /**
     * Cache key part that keeps cached entries of different organizations apart.
     * Used in {@code @Cacheable} keys, e.g. {@code key = "T(com.mailflow.tenant.TenantContext).key(#id)"}.
     */
    public static String key(Object id) {
        return currentOrganizationId().orElse(ROOT) + ":" + id;
    }
}
