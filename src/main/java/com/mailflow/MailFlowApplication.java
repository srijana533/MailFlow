package com.mailflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.freemarker.FreeMarkerAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MailFlow — Scalable Email Campaign Automation Platform
 *
 * <p>Entry point for the Spring Boot application. Enables:
 * <ul>
 *   <li>Component scanning across all sub-packages</li>
 *   <li>Configuration properties binding</li>
 *   <li>Caching (Redis-backed)</li>
 *   <li>Scheduling (campaign launch scheduler)</li>
 *   <li>Async execution (@Async methods)</li>
 * </ul>
 */
@SpringBootApplication(exclude = FreeMarkerAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableCaching
@EnableScheduling
@EnableAsync
public class MailFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailFlowApplication.class, args);
    }
}
