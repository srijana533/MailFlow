package com.mailflow.security;

import com.mailflow.auth.repository.UserRepository;
import com.mailflow.config.MailFlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The V3 migration seeds three demo accounts whose passwords are in the README.
 * On a public deployment ({@code DEMO_ACCOUNTS_ENABLED=false}) they are disabled at
 * startup, so nobody can sign in with them. Their seeded data stays in the "Default"
 * organization and is invisible to every company that signs up.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoAccountGuard {

    static final List<String> DEMO_EMAILS = List.of(
            "admin@mailflow.local", "manager@mailflow.local", "viewer@mailflow.local");

    private final MailFlowProperties properties;
    private final UserRepository userRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void disableDemoAccountsIfConfigured() {
        if (properties.getSecurity().isDemoAccountsEnabled()) {
            log.warn("Demo accounts are ENABLED (published passwords). Set DEMO_ACCOUNTS_ENABLED=false on any public deployment.");
            return;
        }
        int disabled = 0;
        for (String email : DEMO_EMAILS) {
            var user = userRepository.findByEmailIgnoreCase(email);
            if (user.isPresent() && user.get().isEnabled()) {
                user.get().setEnabled(false);
                userRepository.save(user.get());
                disabled++;
            }
        }
        log.info("Demo accounts disabled ({} changed now)", disabled);
    }
}
