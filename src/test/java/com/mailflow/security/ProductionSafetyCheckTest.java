package com.mailflow.security;

import com.mailflow.config.MailFlowProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyCheckTest {

    private static ProductionSafetyCheck check(String secret, boolean demoAccounts, String frontendUrl) {
        MailFlowProperties props = new MailFlowProperties();
        props.getSecurity().getJwt().setSecret(secret);
        props.getSecurity().setDemoAccountsEnabled(demoAccounts);
        ProductionSafetyCheck check = new ProductionSafetyCheck(props);
        ReflectionTestUtils.setField(check, "frontendUrl", frontendUrl);
        return check;
    }

    @Test
    void safe_settings_pass() {
        assertThatCode(() -> check("q9V2sX7mN4pL8rT1wY6zB3cF5hJ0kD2gA9eR7uI4oP1=", false, "https://mailflow.vercel.app")
                .afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void the_default_secret_demo_accounts_and_a_missing_frontend_url_are_each_named() {
        assertThatThrownBy(() -> check(ProductionSafetyCheck.DEFAULT_SECRET, true, "").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("JWT_SECRET").contains("DEMO_ACCOUNTS_ENABLED").contains("FRONTEND_URL"));
    }

    @Test
    void the_placeholder_and_short_secrets_are_refused() {
        for (String secret : new String[] {"your-very-long-and-secure-jwt-secret-at-least-256-bits", "short"}) {
            assertThatThrownBy(() -> check(secret, false, "https://x.vercel.app").afterPropertiesSet())
                    .hasMessageContaining("JWT_SECRET");
        }
    }
}
