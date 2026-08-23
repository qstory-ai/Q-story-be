package com.qstory.backend.identity.config;

import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.security.JwtAuthFilter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT setup. authorizeHttpRequests() permits everything at the framework level - the
 * whole existing public surface (story catalog/content, question/narration pipeline, beta-events,
 * voice-research, shadow review, admin import) must keep working exactly as it does today, and
 * each of those already does its own request-shape/token checks in application code (see
 * StoryImportController.requireAdminToken() for the existing precedent). Spring Security here only
 * owns three things: CORS (replacing the old CorsRequestFilter), parsing the JWT into
 * SecurityContext (JwtAuthFilter), and providing BCrypt. Authorization for the new
 * organization/class endpoints is still a manual per-controller decision via CurrentUserResolver,
 * matching this codebase's existing hand-rolled-check style rather than framework-declarative
 * @PreAuthorize/matcher rules.
 */
@Configuration
public class SecurityConfig {

    private final AppProperties config;
    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(AppProperties config, JwtAuthFilter jwtAuthFilter) {
        this.config = config;
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(config.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "content-type", "authorization",
                "x-qstory-story-id", "x-qstory-scene-id", "x-qstory-anchor-id", "x-qstory-question-round"));
        configuration.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
