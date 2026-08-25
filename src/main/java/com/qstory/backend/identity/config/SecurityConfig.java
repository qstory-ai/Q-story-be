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
 * 무상태(stateless) JWT 설정. authorizeHttpRequests()는 프레임워크 레벨에서 모든 요청을
 * 허용한다 - 기존의 전체 공개 표면(story catalog/content, question/narration 파이프라인,
 * beta-events, voice-research, shadow review, admin import)이 지금과 완전히 동일하게 계속
 * 동작해야 하고, 이들 각각은 이미 애플리케이션 코드 안에서 자체적으로 요청 형태/토큰 검사를
 * 하고 있기 때문이다(기존 선례로 StoryImportController.requireAdminToken() 참고). 여기서
 * Spring Security가 담당하는 것은 딱 세 가지뿐이다: CORS(기존 CorsRequestFilter를 대체),
 * JWT를 파싱해 SecurityContext에 넣는 것(JwtAuthFilter), 그리고 BCrypt 제공. organization/
 * class/story-authoring 엔드포인트에 대한 인가(authorization)는 여전히 컨트롤러별로 수동으로
 * CurrentUserResolver.requireRole(...)을 통해 결정되며, 이는 프레임워크 선언형 @PreAuthorize/
 * matcher 규칙이 아니라 이 코드베이스가 기존에 써 온 수작업 검사(hand-rolled-check) 스타일을
 * 따르는 것이다.
 *
 * <p>이러한 컨트롤러별 검사는 검사 대상 역할(role)만큼만 안전하다: Role.DIRECTOR/
 * CLASS_ACCOUNT/PARENT는 누구나 공개 회원가입을 통해 얻을 수 있는 셀프서비스 역할이므로,
 * 내부 운영자 전용 엔드포인트를 이들 중 하나로 게이트하는 것(story-authoring이 한때 DIRECTOR로
 * 그렇게 했던 것처럼)은 설정 실수가 아니라 권한 상승(privilege-escalation) 버그다. 내부 전용
 * 엔드포인트는 반드시 Role.STAFF로 게이트해야 하며, 이 역할은 고객 대상 회원가입 폼을 통해서는
 * 절대 발급되지 않는다 - Role.java와 AuthController.signupStaff()를 참고.
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
