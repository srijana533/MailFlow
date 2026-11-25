package com.mailflow.tenant;

import com.mailflow.auth.entity.User;
import com.mailflow.organization.entity.Organization;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/** Signs a user of organization {@link #ORG} in for a unit test, as the JWT filter would. */
public final class TestTenant {

    public static final Long ORG = 1L;

    private TestTenant() {}

    public static User signIn() {
        return signIn(ORG);
    }

    public static User signIn(Long organizationId) {
        User user = User.builder()
                .id(100L + organizationId)
                .email("user" + organizationId + "@test.local")
                .firstName("Test").lastName("User")
                .enabled(true)
                .organization(Organization.builder().id(organizationId).name("Org " + organizationId).build())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return user;
    }

    public static void signOut() {
        SecurityContextHolder.clearContext();
    }
}
