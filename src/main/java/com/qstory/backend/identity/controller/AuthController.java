package com.qstory.backend.identity.controller;
import com.qstory.backend.identity.service.AuthService;

import com.qstory.backend.common.util.AdminTokenGuard;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.ChangePasswordRequest;
import com.qstory.backend.identity.dto.ConfirmPasswordResetRequest;
import com.qstory.backend.identity.dto.DeleteAccountRequest;
import com.qstory.backend.identity.dto.LoginRequest;
import com.qstory.backend.identity.dto.RequestPasswordResetRequest;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
import com.qstory.backend.identity.dto.UpdateProfileRequest;
import com.qstory.backend.identity.dto.UserSummary;
import com.qstory.backend.identity.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * 회원가입/로그인/whoami. 반 코드로 가입하는 학부모 회원가입은 ClassController(POST
 * /v1/classes/join)에 있다 - 이 컨트롤러의 signup/parent는 반 코드 없이 가입하는 "독립" 학부모용이다.
 */
@Tag(name = "Auth", description = "Organization-owner/staff signup, role-agnostic login, and current-user lookup")
@RestController
public class AuthController {

    private final AuthService authService;
    private final CurrentUserResolver currentUserResolver;
    private final AdminTokenGuard adminTokenGuard;

    public AuthController(AuthService authService, CurrentUserResolver currentUserResolver, AdminTokenGuard adminTokenGuard) {
        this.authService = authService;
        this.currentUserResolver = currentUserResolver;
        this.adminTokenGuard = adminTokenGuard;
    }

    @Operation(summary = "Sign up as an organization owner",
            description = "Creates a DIRECTOR account with no organization yet - call POST /v1/organizations next.")
    @PostMapping("/v1/auth/signup/organization")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signupOrganizationOwner(@RequestBody SignupOrganizationOwnerRequest request) {
        return authService.signupOrganizationOwner(request);
    }

    @Operation(summary = "Sign up as an independent parent",
            description = "Creates a PARENT account with no organization/class - for a parent whose child isn't "
                    + "enrolled in a partnered kindergarten. Access to entitlement-gated stories then depends only "
                    + "on this account's own subscription (see EntitlementService), never an organization's.")
    @PostMapping("/v1/auth/signup/parent")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signupParent(@RequestBody SignupOrganizationOwnerRequest request) {
        return authService.signupParent(request);
    }

    @Operation(summary = "Sign up as a tutor",
            description = "Creates a TUTOR account - a self-service role for a home-visit/1:1 tutor. No "
                    + "organization/class; the tutor manages their own student roster (see TutorController).")
    @PostMapping("/v1/auth/signup/tutor")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signupTutor(@RequestBody SignupOrganizationOwnerRequest request) {
        return authService.signupTutor(request);
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
        adminTokenGuard.require(httpRequest);
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

    @Operation(summary = "Update the current user's profile",
            description = "displayName is required for every role. childName is only stored for PARENT accounts "
                    + "and silently ignored for any other role.")
    @PostMapping("/v1/auth/me/profile")
    public UserSummary updateProfile(@RequestBody UpdateProfileRequest request) {
        return authService.updateProfile(currentUserResolver.require(), request);
    }

    @Operation(summary = "Upload a tutor profile image", description = "TUTOR only. JPG/PNG, up to 4MB and 2048px per side.")
    @PostMapping(value = "/v1/auth/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserSummary uploadProfileImage(@RequestParam("image") MultipartFile image) {
        return authService.uploadProfileImage(currentUserResolver.require(), image);
    }

    @Operation(summary = "Change the current user's password",
            description = "Requires the current password, unlike password-reset/confirm which is for a forgotten "
                    + "password via email token.")
    @PostMapping("/v1/auth/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestBody ChangePasswordRequest request) {
        authService.changePassword(currentUserResolver.require(), request);
    }

    @Operation(summary = "Delete (soft-delete) the current user's account",
            description = "Records an exit-survey reason, then blocks the account from logging in again. Not a "
                    + "hard delete - other tables (password reset tokens, tutor students, story completions) still "
                    + "reference this row.")
    @PostMapping("/v1/auth/me/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@RequestBody DeleteAccountRequest request) {
        authService.deleteAccount(currentUserResolver.require(), request);
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
}
