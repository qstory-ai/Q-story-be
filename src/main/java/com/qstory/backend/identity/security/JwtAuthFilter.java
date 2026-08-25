package com.qstory.backend.identity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * "Authorization: Bearer <token>"을 파싱하여, 존재하고 유효할 경우 SecurityContext를 채운다.
 * 이 필터 자체는 요청을 거부하지 않도록 의도적으로 만들어졌다 - 이 API의 대부분(story catalog,
 * 익명 데모의 question/narration 파이프라인, signup/login/join-by-code)은 토큰이 전혀 없어도
 * 계속 동작해야 하므로, 거부 여부는 항상 CurrentUserResolver를 통한 엔드포인트 단위의 결정이며
 * 여기서는 절대 이루어지지 않는다.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            Optional<CurrentUser> user = jwtService.verify(token);
            user.ifPresent(this::authenticate);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(CurrentUser user) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
