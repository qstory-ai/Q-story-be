package com.qstory.backend.institution;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 공공 기관 목록의 글자 다듬기 - 적재(importer)와 검색(PublicInstitutionSearchService)이 같은 규칙을 써야 검색이 맞는다.
 *
 * <ul>
 *   <li>searchKey: 공백을 모두 빼고 소문자로. "해바라기 유치원"과 "해바라기유치원"을 같은 말로 본다.</li>
 *   <li>sido: 주소 첫 마디를 지금 시도 이름으로 맞춘다(옛 이름 "강원도", 줄임말 "경기" 등).</li>
 * </ul>
 */
public final class PublicInstitutionText {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    // LIKE 패턴의 특수 문자 - 사용자가 넣은 %, _ 가 와일드카드로 쓰이지 않게 뺀다.
    private static final Pattern LIKE_META = Pattern.compile("[%_\\\\]");

    /** 지금 시도 이름(유치원알리미 2026년 1차 기준 - 광주·전남은 전남광주통합특별시로 합쳐졌다). */
    public static final List<String> SIDO_NAMES = List.of(
            "서울특별시", "부산광역시", "대구광역시", "인천광역시", "대전광역시", "울산광역시", "세종특별자치시",
            "경기도", "강원특별자치도", "충청북도", "충청남도", "전북특별자치도", "전남광주통합특별시",
            "경상북도", "경상남도", "제주특별자치도", "광주광역시", "전라남도");

    private static final Map<String, String> SIDO_ALIASES = Map.ofEntries(
            Map.entry("서울", "서울특별시"), Map.entry("서울시", "서울특별시"),
            Map.entry("부산", "부산광역시"), Map.entry("부산시", "부산광역시"),
            Map.entry("대구", "대구광역시"), Map.entry("대구시", "대구광역시"),
            Map.entry("인천", "인천광역시"), Map.entry("인천시", "인천광역시"),
            Map.entry("대전", "대전광역시"), Map.entry("대전시", "대전광역시"),
            Map.entry("울산", "울산광역시"), Map.entry("울산시", "울산광역시"),
            Map.entry("세종", "세종특별자치시"), Map.entry("세종시", "세종특별자치시"),
            Map.entry("경기", "경기도"),
            Map.entry("강원", "강원특별자치도"), Map.entry("강원도", "강원특별자치도"),
            Map.entry("충북", "충청북도"), Map.entry("충남", "충청남도"),
            Map.entry("전북", "전북특별자치도"), Map.entry("전라북도", "전북특별자치도"),
            Map.entry("경북", "경상북도"), Map.entry("경남", "경상남도"),
            Map.entry("제주", "제주특별자치도"), Map.entry("제주도", "제주특별자치도"));

    private PublicInstitutionText() {}

    public static String searchKey(String text) {
        if (text == null) return "";
        return WHITESPACE.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** 검색어 한 마디를 LIKE에 넣을 수 있게 - 공백·와일드카드를 뺀 searchKey. */
    public static String likeSafeKey(String token) {
        return LIKE_META.matcher(searchKey(token)).replaceAll("");
    }

    /** null/빈칸이면 null, 아니면 앞뒤 공백을 지우고 가운데 연속 공백을 하나로. */
    public static String clean(String text) {
        if (text == null) return null;
        String cleaned = WHITESPACE.matcher(text.strip()).replaceAll(" ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** 주소 첫 마디(또는 교육청 이름 등)를 지금 시도 이름으로. 모르는 이름이면 그대로. */
    public static String normalizeSido(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) return null;
        if (SIDO_NAMES.contains(cleaned)) return cleaned;
        return SIDO_ALIASES.getOrDefault(cleaned, cleaned);
    }

    /** 주소에서 시도와 시군구(둘째 마디)를 뽑는다. 시도가 아닌 말로 시작하면 둘 다 null. */
    public static String[] sidoAndSigungu(String address) {
        String cleaned = clean(address);
        if (cleaned == null) return new String[] {null, null};
        String[] parts = cleaned.split(" ");
        String sido = normalizeSido(parts[0]);
        if (!SIDO_NAMES.contains(sido)) return new String[] {null, null};
        String sigungu = parts.length > 1 && !sido.equals("세종특별자치시") ? parts[1] : null;
        return new String[] {sido, sigungu};
    }

    public static String truncate(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max);
    }
}
