package com.mailflow.email;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Sets {@code mail.smtp.ssl.trust} only when {@code SMTP_SSL_TRUST} has a value.
 *
 * <p>Jakarta Mail treats an empty {@code mail.smtp.ssl.trust} as "trust no server",
 * so STARTTLS failed with "Server is not trusted: smtp-relay.brevo.com" when the
 * property was always set from an empty variable. Without the property, the
 * certificate is checked against the JVM's trust store as usual.
 */
@Configuration
public class SmtpSslTrustConfig {

    static final String PROPERTY = "mail.smtp.ssl.trust";

    @Bean
    static BeanPostProcessor smtpSslTrustPostProcessor(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof JavaMailSenderImpl sender) {
                    apply(sender, environment.getProperty("SMTP_SSL_TRUST"));
                }
                return bean;
            }
        };
    }

    static void apply(JavaMailSenderImpl sender, String trust) {
        if (trust == null || trust.isBlank()) {
            sender.getJavaMailProperties().remove(PROPERTY);
        } else {
            sender.getJavaMailProperties().setProperty(PROPERTY, trust.strip());
        }
    }
}
