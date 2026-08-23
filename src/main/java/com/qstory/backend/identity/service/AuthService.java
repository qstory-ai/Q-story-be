package com.qstory.backend.identity.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.LoginRequest;
import com.qstory.backend.identity.dto.SignupDirectorRequest;
import com.qstory.backend.identity.dto.UserSummary;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.util.AuthValidator;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final AuthValidator validator;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            AppUserRepository userRepository, AuthValidator validator,
            PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.validator = validator;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse signupDirector(SignupDirectorRequest request) {
        validator.validateSignup(request);
        String loginId = request.email().trim().toLowerCase();
        AppUser user = AppUser.builder()
                .role(Role.DIRECTOR)
                .loginId(loginId)
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName().trim())
                .createdAt(Instant.now())
                .build();
        try {
            // saveAndFlush, not save: with a client/pre-generated @UuidGenerator id, Hibernate can
            // defer the INSERT to transaction-commit time, which is after this method (and its
            // catch block) has already returned - flushing here forces the constraint violation to
            // surface synchronously so it's actually catchable.
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException alreadyRegistered) {
            throw ApiException.contractError(ErrorCode.LOGIN_ID_ALREADY_REGISTERED, "이미 등록된 이메일이에요.");
        }
        return issueResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        validator.validateLogin(request);
        AppUser user = userRepository.findByLoginId(request.loginId().trim().toLowerCase())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.INVALID_CREDENTIALS, "아이디 또는 비밀번호가 올바르지 않아요."));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.contractError(ErrorCode.INVALID_CREDENTIALS, "아이디 또는 비밀번호가 올바르지 않아요.");
        }
        return issueResponse(user);
    }

    public UserSummary me(CurrentUser caller) {
        AppUser user = userRepository.findById(caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
        return UserSummary.of(user);
    }

    private AuthResponse issueResponse(AppUser user) {
        CurrentUser currentUser = new CurrentUser(
                user.getId(), user.getRole(),
                user.getOrganization() == null ? null : user.getOrganization().getId(),
                user.getClassGroup() == null ? null : user.getClassGroup().getId());
        return new AuthResponse(jwtService.issue(currentUser), UserSummary.of(user));
    }
}
