package com.qstory.backend.institution.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.institution.InstitutionKind;
import com.qstory.backend.institution.PublicInstitutionText;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 유치원알리미(e-childschoolinfo.moe.go.kr) "공시자료 다운로드"의 일반 현황(공시항목 05) JSON을 읽는다.
 * 모양: {"header": ["교육청명", "교육지원청명", "유치원명", "설립유형", "대표자명", "원장명", "설립일", ..., "주소", ...],
 * "body": [[...], ...]}. 같은 내용의 CSV는 쉼표가 든 칸을 따옴표로 감싸지 않은 줄이 있어(2026년 1차 77줄) JSON만 받는다.
 *
 * <p>열은 이름으로 찾는다(순서가 바뀌어도). 대표자명·원장명·전화·팩스·홈페이지는 읽지 않는다 - 사람 정보이거나 쓰지 않는 값.
 * 다운로드 파일에 유치원 코드가 없어 "교육지원청|유치원명|설립일"을 원천 키로 쓴다.
 */
public final class KindergartenOpenDataParser {

    public static final String SOURCE = "KINDERGARTEN_INFO";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KindergartenOpenDataParser() {}

    public static List<InstitutionRecord> parse(InputStream json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode header = root.path("header");
        JsonNode body = root.path("body");
        if (!header.isArray() || !body.isArray()) {
            throw new IOException("유치원알리미 JSON 모양이 아니에요(header/body 없음).");
        }
        Map<String, Integer> column = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            column.put(header.get(i).asText().strip(), i);
        }
        int office = require(column, "교육지원청명");
        int education = require(column, "교육청명");
        int name = require(column, "유치원명");
        int type = require(column, "설립유형");
        int founded = require(column, "설립일");
        int address = require(column, "주소");

        List<InstitutionRecord> records = new ArrayList<>();
        for (JsonNode row : body) {
            String kinderName = PublicInstitutionText.clean(text(row, name));
            if (kinderName == null) continue;
            String addr = PublicInstitutionText.clean(text(row, address));
            String[] region = PublicInstitutionText.sidoAndSigungu(addr);
            String sido = region[0] != null
                    ? region[0]
                    : PublicInstitutionText.normalizeSido(
                            stripSuffix(PublicInstitutionText.clean(text(row, education)), "교육청"));
            String officeName = PublicInstitutionText.clean(text(row, office));
            String key = String.join("|",
                    nullToEmpty(officeName), kinderName, nullToEmpty(PublicInstitutionText.clean(text(row, founded))));
            records.add(new InstitutionRecord(
                    InstitutionKind.KINDERGARTEN,
                    SOURCE,
                    PublicInstitutionText.truncate(key, 200),
                    PublicInstitutionText.truncate(kinderName, 200),
                    PublicInstitutionText.truncate(addr, 300),
                    PublicInstitutionText.truncate(sido, 40),
                    PublicInstitutionText.truncate(region[1], 40),
                    PublicInstitutionText.truncate(PublicInstitutionText.clean(text(row, type)), 40),
                    true));
        }
        return records;
    }

    private static int require(Map<String, Integer> column, String name) throws IOException {
        Integer index = column.get(name);
        if (index == null) throw new IOException("유치원알리미 JSON에 \"" + name + "\" 열이 없어요.");
        return index;
    }

    private static String text(JsonNode row, int index) {
        JsonNode cell = row.get(index);
        return cell == null || cell.isNull() ? null : cell.asText();
    }

    private static String stripSuffix(String text, String suffix) {
        if (text == null) return null;
        return text.endsWith(suffix) ? text.substring(0, text.length() - suffix.length()) : text;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
