package com.qstory.backend.push.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qstory.backend.common.error.GlobalExceptionHandler;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.push.PushPlatform;
import com.qstory.backend.push.service.PushTokenService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 075: 어느 역할이든 로그인하면 등록·해제 204, 로그인 안 했으면 401, 형식이 틀리면 400. */
class PushTokenControllerTest {

    private final PushTokenService service = mock(PushTokenService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PushTokenController(service, new CurrentUserResolver()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void needsLogin() throws Exception {
        mvc.perform(post("/v1/me/push-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/me/push-tokens/remove").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void registersForAnyRoleAndAcceptsLowercasePlatform() throws Exception {
        for (Role role : Role.values()) {
            UUID user = login(role);
            mvc.perform(post("/v1/me/push-tokens").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\" fcm:APA91b-x_y \",\"platform\":\"android\"}"))
                    .andExpect(status().isNoContent());
            verify(service).register(user, "fcm:APA91b-x_y", PushPlatform.ANDROID);
        }
    }

    @Test
    void badRegistrationIs400() throws Exception {
        login(Role.PARENT);
        for (String body : List.of(
                "{\"platform\":\"IOS\"}",
                "{\"token\":\"\",\"platform\":\"IOS\"}",
                "{\"token\":\"has space\",\"platform\":\"IOS\"}",
                "{\"token\":123,\"platform\":\"IOS\"}",
                "{\"token\":\"" + "a".repeat(513) + "\",\"platform\":\"IOS\"}",
                "{\"token\":\"abc\"}",
                "{\"token\":\"abc\",\"platform\":\"BLACKBERRY\"}",
                "not json", "")) {
            mvc.perform(post("/v1/me/push-tokens").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void removesTheCallersToken() throws Exception {
        UUID user = login(Role.TUTOR);
        mvc.perform(post("/v1/me/push-tokens/remove").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm:abc\"}"))
                .andExpect(status().isNoContent());
        verify(service).remove(user, "fcm:abc");
    }

    @Test
    void badRemovalIs400() throws Exception {
        login(Role.TUTOR);
        for (String body : List.of("{}", "{\"token\":\" \"}", "")) {
            mvc.perform(post("/v1/me/push-tokens/remove").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    private static UUID login(Role role) {
        CurrentUser user = new CurrentUser(UUID.randomUUID(), role, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return user.userId();
    }
}
