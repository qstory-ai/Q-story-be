package com.qstory.backend.institution.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.institution.InstitutionKind;
import com.qstory.backend.institution.PublicInstitutionText;
import com.qstory.backend.institution.dto.PublicInstitutionResponse;
import com.qstory.backend.institution.dto.PublicInstitutionSearchResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 공공 기관 목록 검색 - 원장 기관 등록(가입 화면 포함, 익명)과 선생님 소속 신청이 함께 쓴다. 자주 불리는 경로라
 * 검색어를 꼭 받고(두 글자 이상), 한 번에 최대 MAX_LIMIT곳만 돌려준다. 다음 쪽은 없다 - 더 있으면 hasMore만 알려
 * 검색어를 좁히게 한다. 요청 상한은 RateLimitFilter(public-institution-search).
 *
 * <p>검색어는 공백으로 나눈 마디마다 이름+주소(search_text)에 들어 있어야 한다 - "강릉 가온"처럼 지역과 이름을 섞어 써도 된다.
 * 이름이 검색어와 같거나 검색어로 시작하는 곳을 먼저 보인다.
 */
@Service
public class PublicInstitutionSearchService {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 20;
    static final int MAX_TOKENS = 4;
    static final int MAX_TOKEN_LENGTH = 40;

    public static final String ATTRIBUTION =
            "기관 정보 출처: 교육부 유치원알리미 공시자료, 한국사회보장정보원 어린이집정보공개포털(공공누리 제1유형)";

    private final NamedParameterJdbcTemplate jdbc;

    public PublicInstitutionSearchService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param includeRegistration 로그인한 원장·선생님이면 true - 이미 등록된 기관인지(organizationId)를 함께 준다.
     */
    public PublicInstitutionSearchResponse search(
            String query, String kind, String sido, Integer limit, boolean includeRegistration) {
        List<String> tokens = tokens(query);
        InstitutionKind kindFilter = parseKind(kind);
        String sidoFilter = sido == null || sido.isBlank() ? null : PublicInstitutionText.normalizeSido(sido);
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
                select pi.id, pi.kind, pi.name, pi.address, pi.sido, pi.sigungu, pi.establishment_type,
                       o.id as organization_id
                from public.public_institution pi
                left join public.organization o on o.public_institution_id = pi.id
                where pi.status = 'ACTIVE'
                """);
        if (kindFilter != null) {
            sql.append(" and pi.kind = :kind");
            params.addValue("kind", kindFilter.name());
        }
        if (sidoFilter != null) {
            sql.append(" and pi.sido = :sido");
            params.addValue("sido", sidoFilter);
        }
        for (int i = 0; i < tokens.size(); i++) {
            sql.append(" and pi.search_text like :t").append(i);
            params.addValue("t" + i, "%" + tokens.get(i) + "%");
        }
        String whole = String.join("", tokens);
        params.addValue("exact", whole);
        params.addValue("prefix", tokens.get(0) + "%");
        params.addValue("nameLike", "%" + tokens.get(0) + "%");
        sql.append("""
                 order by case when pi.search_name = :exact then 0
                               when pi.search_name like :prefix then 1
                               when pi.search_name like :nameLike then 2
                               else 3 end,
                          length(pi.name), pi.name, pi.id
                 limit :limit
                """);
        params.addValue("limit", size + 1);

        List<PublicInstitutionResponse> rows = jdbc.query(sql.toString(), params, (rs, n) -> {
            UUID organizationId = includeRegistration ? rs.getObject("organization_id", UUID.class) : null;
            return new PublicInstitutionResponse(
                    rs.getObject("id", UUID.class),
                    rs.getString("kind"),
                    rs.getString("name"),
                    rs.getString("address"),
                    rs.getString("sido"),
                    rs.getString("sigungu"),
                    rs.getString("establishment_type"),
                    includeRegistration ? organizationId != null : null,
                    organizationId);
        });
        boolean hasMore = rows.size() > size;
        return new PublicInstitutionSearchResponse(
                hasMore ? List.copyOf(rows.subList(0, size)) : rows, hasMore, ATTRIBUTION);
    }

    static List<String> tokens(String query) {
        if (query == null) throw tooShort();
        List<String> tokens = new ArrayList<>();
        for (String part : Arrays.stream(query.strip().split("\\s+")).toList()) {
            String key = PublicInstitutionText.likeSafeKey(part);
            if (key.isEmpty()) continue;
            tokens.add(key.length() > MAX_TOKEN_LENGTH ? key.substring(0, MAX_TOKEN_LENGTH) : key);
            if (tokens.size() == MAX_TOKENS) break;
        }
        if (tokens.isEmpty() || String.join("", tokens).length() < 2) throw tooShort();
        return tokens;
    }

    private static InstitutionKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) return null;
        try {
            return InstitutionKind.valueOf(kind.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "기관 종류는 유치원 또는 어린이집이에요.");
        }
    }

    private static ApiException tooShort() {
        return ApiException.contractError(ErrorCode.VALIDATION_FAILED, "기관 이름을 두 글자 이상 입력해 주세요.");
    }
}
