package com.mailflow.email;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpSslTrustConfigTest {

    @Test
    void no_trust_property_without_a_value_so_normal_certificate_checks_apply() {
        for (String blank : new String[] {null, "", "  "}) {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.getJavaMailProperties().setProperty(SmtpSslTrustConfig.PROPERTY, "");
            SmtpSslTrustConfig.apply(sender, blank);
            assertThat(sender.getJavaMailProperties()).doesNotContainKey(SmtpSslTrustConfig.PROPERTY);
        }
    }

    @Test
    void a_configured_trust_host_is_set() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        SmtpSslTrustConfig.apply(sender, " smtp.gmail.com ");
        assertThat(sender.getJavaMailProperties().getProperty(SmtpSslTrustConfig.PROPERTY)).isEqualTo("smtp.gmail.com");
    }

    @Test
    void application_yml_no_longer_sets_an_empty_trust_property() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).doesNotContain("trust: ${SMTP_SSL_TRUST");
    }
}
