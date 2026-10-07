package com.mailflow.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class SeedCredentialsTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @Test
    void verifyAdminPassword() {
        assertThat(encoder.matches("Admin@123", "$2a$12$QAs4bs8wloGrgZh0d0Tc0ud9E6OeQij.KmbcIi8fmHtUpUGs9fd0a")).isTrue();
    }

    @Test
    void verifyManagerPassword() {
        assertThat(encoder.matches("Manager@123", "$2a$12$08kG7KkUDvuh1jLJqY7DOueqKxa3Ic86v7SIymxRWcV3XyLmNVlUC")).isTrue();
    }

    @Test
    void verifyViewerPassword() {
        assertThat(encoder.matches("Viewer@123", "$2a$12$5wz6U1uNpE65GXP/NWL2z.QtJHdMukvTUJNHUHCt9TPVOVckiSU9W")).isTrue();
    }
}

