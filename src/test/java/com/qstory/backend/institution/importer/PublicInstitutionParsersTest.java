package com.qstory.backend.institution.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qstory.backend.institution.InstitutionKind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 원천 파일 읽기. 유치원 JSON은 유치원알리미 2026년 1차 일반 현황 다운로드의 실제 앞부분 모양(열 순서·값)을 그대로 줄였다.
 * 사람 이름(대표자명·원장명)과 전화는 결과 어디에도 남지 않아야 한다.
 */
class PublicInstitutionParsersTest {

    private static final String KINDER_JSON = """
            {"header":["교육청명","교육지원청명","유치원명","설립유형","대표자명","원장명","설립일","개원일","주소","전화번호",
            "팩스번호","홈페이지","운영시간","위도","경도"],
            "body":[
             [" ","원주교육지원청","강원대학교부설유치원","국립","정재연","양유진","19970227","19970331",
              "강원특별자치도 원주시 남원로 150","033-760-8241","033-760-8249","http://x","1300시~1700시","37.3","127.9"],
             ["강원특별자치도교육청","강릉교육지원청","가온유치원","사립(사인)","김양희","김양희","20141015","20150302",
              "강원도  강릉시 하슬라로20번길 15","033-645-1231","033-645-1230","https://x","0900시~1700시","37.7","128.8"],
             ["세종특별자치시교육청","세종특별자치시교육청","한솔 유치원","공립(단설)","박하나","박하나","20120301","20120301",
              "세종특별자치시 한누리대로 1","044-000-0000","","","",null,null],
             ["경기도교육청","수원교육지원청","","공립(병설)","x","x","20000101","20000101","경기도 수원시","","","","","",""]
            ]}
            """;

    @Test
    void kindergartenJsonKeepsInstitutionFieldsOnly() throws IOException {
        List<InstitutionRecord> records = KindergartenOpenDataParser.parse(stream(KINDER_JSON));

        assertEquals(3, records.size(), "이름 없는 줄은 건너뛴다");
        InstitutionRecord national = records.get(0);
        assertEquals(InstitutionKind.KINDERGARTEN, national.kind());
        assertEquals("원주교육지원청|강원대학교부설유치원|19970227", national.sourceKey());
        assertEquals("강원특별자치도", national.sido());
        assertEquals("원주시", national.sigungu());
        assertEquals("국립", national.establishmentType());
        assertTrue(national.active());

        InstitutionRecord gaon = records.get(1);
        assertEquals("강원특별자치도", gaon.sido(), "옛 이름 강원도는 지금 이름으로");
        assertEquals("강원도 강릉시 하슬라로20번길 15", gaon.address(), "연속 공백은 하나로");

        InstitutionRecord sejong = records.get(2);
        assertEquals("세종특별자치시", sejong.sido());
        assertNull(sejong.sigungu(), "세종은 시군구가 없다");

        for (InstitutionRecord record : records) {
            String all = record.toString();
            assertFalse(all.contains("정재연") || all.contains("양유진") || all.contains("김양희") || all.contains("박하나"),
                    "대표자·원장 이름은 남기지 않는다: " + all);
            assertFalse(all.contains("033-"), "전화번호는 남기지 않는다: " + all);
        }
    }

    @Test
    void kindergartenJsonWithoutRequiredColumnFails() {
        assertThrows(IOException.class, () -> KindergartenOpenDataParser.parse(stream("{\"header\":[\"유치원명\"],\"body\":[]}")));
        assertThrows(IOException.class, () -> KindergartenOpenDataParser.parse(stream("{\"rows\":[]}")));
    }

    private static final String CHILDCARE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <response>
              <item><sidoname>서울특별시</sidoname><sigunname>종로구</sigunname><stcode>11110000001</stcode>
                <crname>햇살어린이집</crname><crtypename>국공립</crtypename><crstatusname>정상</crstatusname>
                <craddr>서울특별시 종로구 세종대로 1</craddr><crtelno>02-000-0000</crtelno><crrepname>홍길동</crrepname></item>
              <item><sidoname>서울특별시</sidoname><sigunname>종로구</sigunname><stcode>11110000002</stcode>
                <crname>달빛어린이집</crname><crtypename>가정</crtypename><crstatusname>폐지</crstatusname>
                <craddr></craddr></item>
              <item><stcode></stcode><crname>코드없음</crname></item>
            </response>
            """;

    @Test
    void childcareXmlUsesSourceCodeAndMarksClosed() throws IOException {
        List<InstitutionRecord> records = ChildcareApiParser.parse(stream(CHILDCARE_XML));

        assertEquals(2, records.size());
        InstitutionRecord open = records.get(0);
        assertEquals(InstitutionKind.DAYCARE, open.kind());
        assertEquals("11110000001", open.sourceKey());
        assertEquals("종로구", open.sigungu());
        assertEquals("국공립", open.establishmentType());
        assertTrue(open.active());
        assertFalse(open.toString().contains("홍길동") || open.toString().contains("02-000"));

        InstitutionRecord closed = records.get(1);
        assertFalse(closed.active(), "폐지는 검색에서 뺀다");
        assertEquals("서울특별시", closed.sido(), "주소가 없으면 원천의 시도 이름");
    }

    @Test
    void childcareErrorAndDoctypeAreRejected() {
        assertThrows(IOException.class, () -> ChildcareApiParser.parse(stream(
                "<response><errmsg>인증키가 유효하지 않습니다.</errmsg><errcode>INFO-100</errcode></response>")));
        assertThrows(IOException.class, () -> ChildcareApiParser.parse(stream(
                "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><response><item>&x;</item></response>")));
    }

    @Test
    void dedupeKeepsLastRowPerKey() {
        InstitutionRecord a = new InstitutionRecord(InstitutionKind.DAYCARE, "S", "1", "가", null, null, null, null, true);
        InstitutionRecord b = new InstitutionRecord(InstitutionKind.DAYCARE, "S", "1", "나", null, null, null, null, true);
        InstitutionRecord c = new InstitutionRecord(InstitutionKind.DAYCARE, "S", "2", "다", null, null, null, null, true);
        assertEquals(List.of(b, c), PublicInstitutionImporter.dedupe(List.of(a, b, c)));
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.strip().getBytes(StandardCharsets.UTF_8));
    }
}
