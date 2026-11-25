package com.mailflow.auth;

import java.util.List;
import java.util.Set;

/**
 * The three roles are levels: each includes the ones below it. A user stores every
 * role of their level (an Admin has ADMIN, CAMPAIGN_MANAGER and VIEWER), which is
 * what the security rules check.
 */
public final class RoleLevels {

    public static final String ADMIN = "ADMIN";
    public static final String CAMPAIGN_MANAGER = "CAMPAIGN_MANAGER";
    public static final String VIEWER = "VIEWER";

    /** Highest first. */
    public static final List<String> ORDER = List.of(ADMIN, CAMPAIGN_MANAGER, VIEWER);

    private RoleLevels() {}

    /** Every role a user of {@code level} holds; throws for an unknown level. */
    public static Set<String> rolesFor(String level) {
        return switch (level) {
            case ADMIN -> Set.of(ADMIN, CAMPAIGN_MANAGER, VIEWER);
            case CAMPAIGN_MANAGER -> Set.of(CAMPAIGN_MANAGER, VIEWER);
            case VIEWER -> Set.of(VIEWER);
            default -> throw new IllegalArgumentException("Unknown role: " + level);
        };
    }

    /** The highest level among {@code roleNames}, or VIEWER when none is known. */
    public static String levelOf(Set<String> roleNames) {
        for (String r : ORDER) {
            if (roleNames.contains(r)) return r;
        }
        return VIEWER;
    }
}
