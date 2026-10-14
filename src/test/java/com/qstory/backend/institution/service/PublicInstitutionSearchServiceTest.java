package com.qstory.backend.institution.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.institution.PublicInstitutionText;
import com.qstory.backend.institution.dto.PublicInstitutionResponse;
import com.qstory.backend.institution.dto.PublicInstitutionSearchResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** 검색어 다듬기·상한. SQL 자체는 로컬 Postgres에서 실제 데이터(유치원 7,152곳)로 확인했다(PR 설명). */
class PublicInstitutionSearchServiceTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PublicInstitutionSearchService service = new PublicInstitutionSearchService(jdbc);

    @Test
    void tokensDropWildcardsAndSpaces() {
        assertEquals(List.of("강릉", "가온유치원"), PublicInstitutionSearchService.tokens("  강릉   가온유치원 "));
        assertEquals(List.of("해바라기"), PublicInstitutionSearchService.tokens("해바%라_기"));
        assertEquals(4, PublicInstitutionSearchService.tokens("가가 나나 다다 라라 마마").size());
    }

    @Test
    void tooShortQueryIsRejected() {
        assertThrows(ApiException.class, () -> PublicInstitutionSearchService.tokens(null));
        assertThrows(ApiException.class, () -> PublicInstitutionSearchService.tokens(" "));
        assertThrows(ApiException.class, () -> PublicInstitutionSearchService.tokens("가"));
        assertThrows(ApiException.class, () -> PublicInstitutionSearchService.tokens("%%"));
    }

    @Test
    void badKindIsRejected() {
        assertThrows(ApiException.class, () -> service.search("햇살", "SCHOOL", null, null, false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void limitIsCappedAndHasMoreReported() {
        List<PublicInstitutionResponse> rows = new ArrayList<>();
        for (int i = 0; i < PublicInstitutionSearchService.MAX_LIMIT + 1; i++) {
            rows.add(new PublicInstitutionResponse(UUID.randomUUID(), "KINDERGARTEN", "햇살" + i, null, null, null, null, null, null));
        }
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(rows);

        PublicInstitutionSearchResponse response = service.search("햇살", "kindergarten", "서울", 500, false);

        assertEquals(PublicInstitutionSearchService.MAX_LIMIT, response.items().size());
        assertTrue(response.hasMore());
        assertFalse(response.attribution().isBlank());
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        MapSqlParameterSource map = (MapSqlParameterSource) params.getValue();
        assertEquals(PublicInstitutionSearchService.MAX_LIMIT + 1, map.getValue("limit"));
        assertEquals("KINDERGARTEN", map.getValue("kind"));
        assertEquals("서울특별시", map.getValue("sido"), "시도 줄임말은 지금 이름으로");
        assertEquals("%햇살%", map.getValue("t0"));
        assertTrue(sql.getValue().contains("pi.status = 'ACTIVE'"));
    }

    @Test
    void textHelpers() {
        assertEquals("해바라기유치원", PublicInstitutionText.searchKey(" 해바라기  유치원 "));
        String[] region = PublicInstitutionText.sidoAndSigungu("경기 수원시 장안구 정자로 1");
        assertEquals("경기도", region[0]);
        assertEquals("수원시", region[1]);
        String[] unknown = PublicInstitutionText.sidoAndSigungu("어딘가 123");
        assertEquals(null, unknown[0]);
    }
}
