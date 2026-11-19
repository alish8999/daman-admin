package com.daman.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:4201",
                        "http://localhost:4200",
                        "http://localhost:3000",
                        "http://localhost:5173",
                        // VS Code Live Server, which serves the BUILT admin frontend statically
                        // (added 2026-11-04: the changelog library failed CORS from here).
                        "http://localhost:5500",
                        "http://127.0.0.1:5500",
                        "https://admin.damansoft.com"
                )
                // PATCH added 2026-11-19 with the announcements publish button. A method missing
                // from this list fails at the browser's preflight, so the call never reaches the
                // controller and the only symptom is a CORS error with no server log line.
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
