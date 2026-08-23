package com.qstory.backend.identity.controller;
import com.qstory.backend.identity.service.AuthService;

import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.LoginRequest;
import com.qstory.backend.identity.dto.SignupDirectorRequest;
import com.qstory.backend.identity.dto.UserSummary;
import com.qstory.backend.identity.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Signup/login/whoami. Parent signup lives on ClassController (POST /v1/classes/join) since it's join-code-driven. */
@Tag(name = "Auth", description = "Director signup, role-agnostic login, and current-user lookup")
@RestController
public class AuthController {

    private final AuthService authService;
    private final CurrentUserResolver currentUserResolver;

    public AuthController(AuthService authService, CurrentUserResolver currentUserResolver) {
        this.authService = authService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Sign up as a kindergarten director",
            description = "Creates a DIRECTOR account with no organization yet - call POST /v1/organizations next.")
    @PostMapping("/v1/auth/signup/director")
    public AuthResponse signupDirector(@RequestBody SignupDirectorRequest request) {
        return authService.signupDirector(request);
    }

    @Operation(summary = "Log in", description = "loginId is an email for DIRECTOR/PARENT or a director-issued handle for a CLASS_ACCOUNT.")
    @PostMapping("/v1/auth/login")
    public AuthResponse login(@RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Get the current logged-in user")
    @GetMapping("/v1/auth/me")
    public UserSummary me() {
        return authService.me(currentUserResolver.require());
    }
}
