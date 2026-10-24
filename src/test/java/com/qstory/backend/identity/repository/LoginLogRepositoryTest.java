package com.qstory.backend.identity.repository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.UserSummary;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** 바인딩 값과 실패 처리. SQL 자체는 운영 DB 트랜잭션 롤백으로 확인했다(PR 설명). */
class LoginLogRepositoryTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final LoginLogRepository repository = new LoginLogRepository(jdbc);
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static AuthResponse response(UUID id) {
        UserSummary user = new UserSummary(id, "PARENT", "parent", null, "보호자", null, "NONE", false, null, null, null,
                List.of());
        return new AuthResponse("token", user);
    }

    @Test
    void recordsUserRoleAndMethod() {
        repository.record(response(USER), "PASSWORD");

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(eq(LoginLogRepository.INSERT), params.capture());
        assertEquals(USER, params.getValue().getValue("userId"));
        assertEquals("PARENT", params.getValue().getValue("role"));
        assertEquals("PASSWORD", params.getValue().getValue("method"));
    }

    @Test
    void skipsResponsesWithoutUser() {
        repository.record(new AuthResponse("token", null), "REFRESH");
        repository.record(null, "REFRESH");

        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void loginStillSucceedsWhenLoggingFails() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(() -> repository.record(response(USER), "GOOGLE"));
    }
}
