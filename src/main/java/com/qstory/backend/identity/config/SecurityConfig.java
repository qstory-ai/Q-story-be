package com.qstory.backend.identity.config;

import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.security.JwtAuthFilter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
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
 * 무상태(stateless) JWT 설정. authorizeHttpRequests()는 프레임워크 레벨에서 모든 요청을 허용한다 -
 * 공개 표면(story catalog/content, question/narration 파이프라인, beta-events, voice-research 등)은
 * 토큰 없이도 동작해야 하고, 각 엔드포인트가 애플리케이션 코드 안에서 자체적으로 요청/토큰 검사를 한다.
 * 여기서 Spring Security가 담당하는 것은 CORS, JWT를 파싱해 SecurityContext에 넣는 것(JwtAuthFilter),
 * BCrypt 제공 세 가지뿐이다. 인가는 컨트롤러별로 CurrentUserResolver.requireRole(...)이 결정한다.
 *
 * <p>이 검사는 검사 대상 역할만큼만 안전하다: DIRECTOR/PARENT/TUTOR는 공개 회원가입으로 누구나 얻는
 * 역할이므로, 내부 운영자 전용 엔드포인트를 이들로 게이트하면 권한 상승 버그다. 내부 전용 엔드포인트는
 * 반드시 Role.STAFF로 게이트한다(Role.java, AuthController.signupStaff() 참고).
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
        configuration.setAllowedOrigins(effectiveAllowedOrigins());
        // 웹 배포는 같은 출처의 Vercel 프록시(/api/qstory)를 거치므로 CORS가 적용되지 않는다. 이 설정은
        // 프록시 없이 백엔드를 직접 부르는 네이티브 셸(.env.native)과 로컬 개발(.env.local)에만 걸린다.
        // 프론트엔드는 PATCH(자녀·알림 설정·수업 등)와 DELETE(북마크·알림·학생 등)도 쓰므로 함께 허용한다.
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "content-type", "authorization",
                "x-qstory-story-id", "x-qstory-scene-id", "x-qstory-anchor-id", "x-qstory-question-round",
                "x-qstory-session-id", "x-qstory-child-id", "x-qstory-tutor-student-id",
                "x-qstory-lesson-id", "x-qstory-input-mode"));
        // 브라우저는 cross-origin 응답에서 CORS-safelisted 헤더만 읽을 수 있다. 내레이션 스트림의 PCM
        // 포맷 헤더와 요청 ID를 노출하지 않으면 프론트엔드가 조용히 기본값(24kHz)으로 떨어진다.
        configuration.setExposedHeaders(List.of(
                "x-qstory-request-id", "x-qstory-audio-sample-rate", "x-qstory-audio-channels",
                "x-qstory-audio-bit-depth", "x-qstory-generation-id"));
        configuration.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * 환경별 allowed-origins(프로필 yml 또는 ALLOWED_ORIGINS 환경변수가 통째로 결정) + 네이티브 앱
     * WebView 출처(application.yml의 qstory.native-origins, 환경과 무관하게 항상).
     */
    List<String> effectiveAllowedOrigins() {
        return Stream.concat(
                        Optional.ofNullable(config.allowedOrigins()).orElseGet(List::of).stream(),
                        Optional.ofNullable(config.nativeOrigins()).orElseGet(List::of).stream())
                .distinct()
                .toList();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
