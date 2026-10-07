package com.qstory.backend.identity.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.AdminTokenGuard;
import com.qstory.backend.identity.service.LegacyDeletedAccountCleanupService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;

class AccountAdminControllerTest {

    private final AdminTokenGuard guard = mock(AdminTokenGuard.class);
    private final LegacyDeletedAccountCleanupService cleanup = mock(LegacyDeletedAccountCleanupService.class);
    private final AccountAdminController controller = new AccountAdminController(guard, cleanup);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void pathIsTheAdminNamespace() throws Exception {
        PostMapping mapping = AccountAdminController.class
                .getMethod("eraseLegacyDeleted", jakarta.servlet.http.HttpServletRequest.class, boolean.class)
                .getAnnotation(PostMapping.class);
        assertEquals("/v1/admin/accounts/erase-legacy-deleted", mapping.value()[0]);
    }

    @Test
    void dryRunReturnsOnlyTheCount() {
        when(cleanup.countPending()).thenReturn(7L);

        Map<String, Object> body = controller.eraseLegacyDeleted(request, true);

        assertEquals(Map.of("ok", true, "dryRun", true, "found", 7L), body);
        verify(guard).require(request);
        verify(cleanup, never()).run();
    }

    @Test
    void runReturnsFoundErasedFailed() {
        when(cleanup.run()).thenReturn(new LegacyDeletedAccountCleanupService.Result(3, 2, 1));

        Map<String, Object> body = controller.eraseLegacyDeleted(request, false);

        assertEquals(Map.of("ok", true, "dryRun", false, "found", 3, "erased", 2, "failed", 1), body);
    }

    @Test
    void wrongAdminTokenTouchesNothing() {
        doThrow(ApiException.contractError(ErrorCode.FORBIDDEN, "no", 403)).when(guard).require(any());

        assertThrows(ApiException.class, () -> controller.eraseLegacyDeleted(request, false));
        assertThrows(ApiException.class, () -> controller.eraseLegacyDeleted(request, true));

        verifyNoInteractions(cleanup);
    }
}
