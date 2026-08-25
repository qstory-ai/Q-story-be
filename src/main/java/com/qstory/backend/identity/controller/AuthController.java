package com.qstory.backend.identity.controller;
import com.qstory.backend.identity.service.AuthService;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.ConfirmPasswordResetRequest;
import com.qstory.backend.identity.dto.LoginRequest;
import com.qstory.backend.identity.dto.RequestPasswordResetRequest;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
import com.qstory.backend.identity.dto.UserSummary;
import com.qstory.backend.identity.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 회원가입/로그인/whoami. 학부모(Parent) 회원가입은 참여 코드(join-code) 기반이라 ClassController(POST /v1/classes/join)에 있다. */
@Tag(name = "Auth", description = "Organization-owner/staff signup, role-agnostic login, and current-user lookup")
@RestController
public class AuthController {

    private final AuthService authService;
    private final CurrentUserResolver currentUserResolver;
    private final AppProperties config;

    public AuthController(AuthService authService, CurrentUserResolver currentUserResolver, AppProperties config) {
        this.authService = authService;
        this.currentUserResolver = currentUserResolver;
        this.config = config;
    }

    @Operation(summary = "Sign up as an organization owner",
            description = "Creates a DIRECTOR account with no organization yet - call POST /v1/organizations next.")
    @PostMapping("/v1/auth/signup/organization")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signupOrganizationOwner(@RequestBody SignupOrganizationOwnerRequest request) {
        return authService.signupOrganizationOwner(request);
    }

    @Operation(summary = "Sign up an internal content-authoring account",
            description = "Creates a STAFF account - the role StoryAuthoringController/NarrationRerenderController "
                    + "require. Not reachable from the app; gated by the same X-Admin-Token shared secret as "
                    + "POST /v1/admin/stories/import, so only whoever holds that secret can mint one.")
    @PostMapping("/v1/auth/signup/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signupStaff(
            @Parameter(in = ParameterIn.HEADER, name = "X-Admin-Token", required = true,
                    description = "Shared secret, must equal qstory.admin.story-import-token") HttpServletRequest httpRequest,
            @RequestBody SignupOrganizationOwnerRequest request) {
        requireAdminToken(httpRequest);
        return authService.signupStaff(request);
    }

    @Operation(summary = "Log in", description = "loginId is an email for DIRECTOR/PARENT/STAFF or an organization-owner-issued handle for a CLASS_ACCOUNT.")
    @PostMapping("/v1/auth/login")
    public AuthResponse login(@RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Get the current logged-in user")
    @GetMapping("/v1/auth/me")
    public UserSummary me() {
        return authService.me(currentUserResolver.require());
    }

    @Operation(summary = "Request a password reset",
            description = "Always responds 200 regardless of whether loginId matches an account, so this can't be "
                    + "used to probe which emails are registered. No email delivery is wired up yet - the reset "
                    + "token is only reachable through server logs until an email provider is configured.")
    @PostMapping("/v1/auth/password-reset/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestPasswordReset(@RequestBody RequestPasswordResetRequest request) {
        authService.requestPasswordReset(request);
    }

    @Operation(summary = "Complete a password reset",
            description = "token is the one-time secret from requestPasswordReset. Logs the caller in on success, "
                    + "same as signup/login.")
    @PostMapping("/v1/auth/password-reset/confirm")
    public AuthResponse confirmPasswordReset(@RequestBody ConfirmPasswordResetRequest request) {
        return authService.confirmPasswordReset(request);
    }

    private void requireAdminToken(HttpServletRequest request) {
        if (!config.admin().storyImportTokenConfigured()) {
            throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "이 기능은 아직 준비되지 않았어요.", 500);
        }
        String provided = request.getHeader("X-Admin-Token");
        if (!DigestUtil.matchesAdminToken(provided, config.admin().storyImportToken())) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 작업을 수행할 권한이 없어요.", 403);
        }
    }
}
