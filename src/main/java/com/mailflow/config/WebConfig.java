package com.mailflow.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration: CORS policy for the React frontend.
 *
 * <p>Local development origins (Vite dev server, Docker frontend) are always
 * allowed. For a deployed environment, set {@code FRONTEND_URL} (comma-separated
 * values are supported) and those origins are added to the allow-list — so the
 * deployed frontend domain works without a code change.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Deployed frontend origin(s). Comma-separated. Empty by default so local
     * development is unaffected. Example: https://mailflow.vercel.app
     */
    @Value("${FRONTEND_URL:}")
    private String frontendUrl;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = new ArrayList<>(List.of(
            "http://localhost:3000",
            "http://localhost:5173",
            "http://localhost:8080"
        ));
        // Add deployed frontend origin(s) from the environment, if configured.
        if (frontendUrl != null && !frontendUrl.isBlank()) {
            for (String o : frontendUrl.split(",")) {
                String trimmed = o.trim();
                if (!trimmed.isEmpty()) {
                    origins.add(trimmed);
                }
            }
        }
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "X-Total-Count", "X-Request-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/v3/api-docs/**", config);
        source.registerCorsConfiguration("/swagger-ui/**", config);
        return source;
    }
}
