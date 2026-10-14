package com.qstory.backend.institution.importer;

import com.qstory.backend.institution.InstitutionKind;
import com.qstory.backend.institution.PublicInstitutionText;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * 어린이집정보공개포털 오픈API "어린이집 기본정보 조회"(cpmsapi030, 시군구 코드 arcode마다 한 번) XML을 읽는다.
 * 모양: response 아래 item마다 sidoname, sigunname, stcode(어린이집 코드), crname, crtypename, crstatusname, craddr ….
 * 인증키가 틀리면 item 없이 errmsg가 온다.
 *
 * <p>대표자명(crrepname)·전화(crtelno)·팩스는 읽지 않는다. 운영현황이 정상·재개가 아니면(휴지·폐지) 검색에서 빼도록 active=false.
 * 이 API는 인증키가 있어야 해서 실제 응답으로는 아직 확인하지 못했다 - 필드 이름은 공개된 API 명세 기준이다.
 */
public final class ChildcareApiParser {

    public static final String SOURCE = "CHILDCARE_INFO";

    private static final Set<String> ACTIVE_STATUSES = Set.of("정상", "재개");

    private ChildcareApiParser() {}

    public static List<InstitutionRecord> parse(InputStream xml) throws IOException {
        Document document = read(xml);
        NodeList items = document.getElementsByTagName("item");
        NodeList errors = document.getElementsByTagName("errmsg");
        if (items.getLength() == 0 && errors.getLength() > 0) {
            throw new IOException("어린이집 API 오류: " + errors.item(0).getTextContent().strip());
        }
        List<InstitutionRecord> records = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String code = PublicInstitutionText.clean(child(item, "stcode"));
            String name = PublicInstitutionText.clean(child(item, "crname"));
            if (code == null || name == null) continue;
            String address = PublicInstitutionText.clean(child(item, "craddr"));
            String[] region = PublicInstitutionText.sidoAndSigungu(address);
            String sido = region[0] != null ? region[0] : PublicInstitutionText.normalizeSido(child(item, "sidoname"));
            String sigungu = region[1] != null ? region[1] : PublicInstitutionText.clean(child(item, "sigunname"));
            String status = PublicInstitutionText.clean(child(item, "crstatusname"));
            records.add(new InstitutionRecord(
                    InstitutionKind.DAYCARE,
                    SOURCE,
                    PublicInstitutionText.truncate(code, 200),
                    PublicInstitutionText.truncate(name, 200),
                    PublicInstitutionText.truncate(address, 300),
                    PublicInstitutionText.truncate(sido, 40),
                    PublicInstitutionText.truncate(sigungu, 40),
                    PublicInstitutionText.truncate(PublicInstitutionText.clean(child(item, "crtypename")), 40),
                    status == null || ACTIVE_STATUSES.contains(status)));
        }
        return records;
    }

    private static Document read(InputStream xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 외부 엔티티(XXE)를 막는다 - 응답은 남의 서버에서 온다.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(xml);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("어린이집 API XML을 읽지 못했어요: " + e.getMessage(), e);
        }
    }

    private static String child(Element item, String tag) {
        for (Node node = item.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().toLowerCase(Locale.ROOT).equals(tag)) {
                return element.getTextContent();
            }
        }
        return null;
    }
}
