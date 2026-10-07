package com.mailflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.TemplateExceptionHandler;

/**
 * FreeMarker configuration for dynamic email body rendering.
 *
 * <p>We configure FreeMarker manually (using a {@link StringTemplateLoader}
 * backed by the database) rather than the Spring Boot MVC auto-configuration.
 * {@code spring.freemarker.enabled=false} in application.yml disables the
 * Spring Boot auto-config so this class is the sole owner.
 *
 * <p>Note: we do NOT import {@code freemarker.template.Configuration} by
 * short name here to avoid the ambiguity with Spring's own
 * {@code @Configuration} annotation — we reference it fully qualified.
 */
@Configuration
public class FreemarkerConfig {

    @Bean
    public StringTemplateLoader stringTemplateLoader() {
        return new StringTemplateLoader();
    }

    /**
     * Marked {@code @Primary} so it wins over Spring Boot's auto-configured
     * {@code freeMarkerConfiguration} bean (from the servlet web FreeMarker
     * auto-config, which is still present even with spring.freemarker.enabled=false).
     */
    @Bean(name = "mailflowFreemarkerConfiguration")
    @Primary
    public freemarker.template.Configuration mailflowFreemarkerConfiguration(
            StringTemplateLoader loader) {

        freemarker.template.Configuration config =
                new freemarker.template.Configuration(
                        freemarker.template.Configuration.VERSION_2_3_32);

        config.setTemplateLoader(loader);
        config.setDefaultEncoding("UTF-8");
        config.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        config.setLogTemplateExceptions(false);
        config.setWrapUncheckedExceptions(true);
        config.setFallbackOnNullLoopVariable(false);
        // Prevent templates from accessing arbitrary Java classes
        config.setNewBuiltinClassResolver(
                freemarker.core.TemplateClassResolver.SAFER_RESOLVER);

        return config;
    }
}
