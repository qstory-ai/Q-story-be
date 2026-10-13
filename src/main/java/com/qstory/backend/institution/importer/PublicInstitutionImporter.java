package com.qstory.backend.institution.importer;

import com.qstory.backend.institution.InstitutionKind;
import com.qstory.backend.institution.PublicInstitutionText;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 다듬은 기관 목록을 public_institution에 넣는다(JDBC, 한 트랜잭션). 같은 (kind, source_key)는 고쳐 쓰고(upsert),
 * closeMissing이면 이번 적재에 없는 같은 원천의 기관을 CLOSED로 돌린다 - 지우지 않는다(기관이 가리키고 있을 수 있다).
 *
 * <p>잘못 받은 일부 파일(한 시도만 등)로 전국 목록을 닫아 버리지 않게, 이번 건수가 지금 운영 중 건수의 절반보다 적으면
 * closeMissing을 거절한다.
 */
public final class PublicInstitutionImporter {

    static final int BATCH_SIZE = 500;

    private static final String UPSERT = """
            insert into public.public_institution
                (kind, source, source_key, name, address, sido, sigungu, establishment_type, status,
                 source_reference, import_batch, search_name, search_text, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
            on conflict (kind, source_key) do update set
                source = excluded.source, name = excluded.name, address = excluded.address,
                sido = excluded.sido, sigungu = excluded.sigungu, establishment_type = excluded.establishment_type,
                status = excluded.status, source_reference = excluded.source_reference,
                import_batch = excluded.import_batch, search_name = excluded.search_name,
                search_text = excluded.search_text, updated_at = now()
            """;

    private static final String COUNT_ACTIVE =
            "select count(*) from public.public_institution where kind = ? and source = ? and status = 'ACTIVE'";

    private static final String CLOSE_MISSING = """
            update public.public_institution set status = 'CLOSED', updated_at = now()
            where kind = ? and source = ? and status = 'ACTIVE' and import_batch is distinct from ?
            """;

    public record Result(int upserted, int closed) {}

    private PublicInstitutionImporter() {}

    /** 같은 키가 파일에 두 번 나오면 마지막 줄을 쓴다(ON CONFLICT는 한 문장 안 중복을 못 받는다 - 배치는 문장마다라 괜찮지만 셈을 맞춘다). */
    static List<InstitutionRecord> dedupe(List<InstitutionRecord> records) {
        Map<String, InstitutionRecord> byKey = new LinkedHashMap<>();
        for (InstitutionRecord record : records) {
            byKey.put(record.kind() + "\u0000" + record.sourceKey(), record);
        }
        return List.copyOf(byKey.values());
    }

    public static Result importRecords(
            Connection connection, InstitutionKind kind, String source, List<InstitutionRecord> records,
            String sourceReference, String importBatch, boolean closeMissing) throws SQLException {
        List<InstitutionRecord> unique = dedupe(records);
        for (InstitutionRecord record : unique) {
            if (record.kind() != kind || !record.source().equals(source)) {
                throw new IllegalArgumentException("한 번에 한 원천만 넣어요: " + record.kind() + "/" + record.source());
            }
        }
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int activeBefore = countActive(connection, kind, source);
            if (closeMissing && unique.size() * 2 < activeBefore) {
                throw new IllegalStateException("이번 적재 " + unique.size() + "건이 지금 운영 중 " + activeBefore
                        + "건의 절반도 안 돼요. 파일이 전체 목록인지 확인하고, 맞으면 --close-missing 없이 넣어 주세요.");
            }
            int upserted = 0;
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                int pending = 0;
                for (InstitutionRecord record : unique) {
                    String searchName = PublicInstitutionText.truncate(PublicInstitutionText.searchKey(record.name()), 200);
                    String searchText = PublicInstitutionText.truncate(
                            searchName + "|" + PublicInstitutionText.searchKey(record.address()), 520);
                    int i = 1;
                    statement.setString(i++, record.kind().name());
                    statement.setString(i++, record.source());
                    statement.setString(i++, record.sourceKey());
                    statement.setString(i++, record.name());
                    statement.setString(i++, record.address());
                    statement.setString(i++, record.sido());
                    statement.setString(i++, record.sigungu());
                    statement.setString(i++, record.establishmentType());
                    statement.setString(i++, record.active() ? "ACTIVE" : "CLOSED");
                    statement.setString(i++, PublicInstitutionText.truncate(sourceReference, 80));
                    statement.setString(i++, PublicInstitutionText.truncate(importBatch, 40));
                    statement.setString(i++, searchName);
                    statement.setString(i, searchText);
                    statement.addBatch();
                    if (++pending == BATCH_SIZE) {
                        upserted += sum(statement.executeBatch());
                        pending = 0;
                    }
                }
                if (pending > 0) upserted += sum(statement.executeBatch());
            }
            int closed = 0;
            if (closeMissing) {
                try (PreparedStatement statement = connection.prepareStatement(CLOSE_MISSING)) {
                    statement.setString(1, kind.name());
                    statement.setString(2, source);
                    statement.setString(3, importBatch);
                    closed = statement.executeUpdate();
                }
            }
            connection.commit();
            return new Result(upserted, closed);
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static int countActive(Connection connection, InstitutionKind kind, String source) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(COUNT_ACTIVE)) {
            statement.setString(1, kind.name());
            statement.setString(2, source);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int sum(int[] counts) {
        int total = 0;
        for (int count : counts) total += count >= 0 ? count : 1; // SUCCESS_NO_INFO(-2)도 한 건으로
        return total;
    }
}
