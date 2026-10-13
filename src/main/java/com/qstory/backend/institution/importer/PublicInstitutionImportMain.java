package com.qstory.backend.institution.importer;

import com.qstory.backend.institution.InstitutionKind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 공공데이터 기관 목록 적재 명령. 앱 서버가 아니라 운영자가 한 번씩 돌린다(유치원 공시는 1년에 2번, 어린이집은 분기마다 정도).
 * 원천 파일은 저장소에 넣지 않는다 - 내려받아 바로 넣거나, 받아 둔 파일 경로를 준다.
 *
 * <pre>
 * # 유치원 - 유치원알리미 공시자료 다운로드(키 없음, 공공누리 제1유형). --timing은 공시 차수(2026년 1차 = 20261).
 * ./gradlew importPublicInstitutions -Pargs="kindergarten --timing 20261 --dry-run"
 * ./gradlew importPublicInstitutions -Pargs="kindergarten --timing 20261 --close-missing"
 * ./gradlew importPublicInstitutions -Pargs="kindergarten --timing 20261 --file C:/data/kinder-20261.json"
 *
 * # 어린이집 - 어린이집정보공개포털 오픈API(인증키 CHILDCARE_API_KEY 필요). 시군구 코드(5자리) 목록 파일이 있어야 한다.
 * ./gradlew importPublicInstitutions -Pargs="daycare --arcodes-file C:/data/arcodes.txt --save-dir C:/data/daycare --dry-run"
 * ./gradlew importPublicInstitutions -Pargs="daycare --xml-dir C:/data/daycare --reference 2026-10-13"
 * </pre>
 *
 * DB는 앱과 같은 SUPABASE_DB_URL / SUPABASE_DB_USERNAME / SUPABASE_DB_PASSWORD 환경 변수로 붙는다(--dry-run이면 붙지 않는다).
 * 083 마이그레이션이 먼저 돌아 있어야 한다(앱 배포가 먼저).
 */
public final class PublicInstitutionImportMain {

    static final String KINDERGARTEN_DOWNLOAD = "https://e-childschoolinfo.moe.go.kr/download/getTotalOpenData.do";
    static final String CHILDCARE_API = "https://api.childcare.go.kr/mediate/rest/cpmsapi030/cpmsapi030/request";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private PublicInstitutionImportMain() {}

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) {
            usage();
            System.exit(2);
        }
        String command = argv[0];
        Map<String, String> options = parseOptions(argv);
        boolean dryRun = options.containsKey("dry-run");
        boolean closeMissing = options.containsKey("close-missing");
        String batch = "import-" + LocalDate.now() + "-" + System.currentTimeMillis() % 100000;

        InstitutionKind kind;
        String source;
        String reference;
        List<InstitutionRecord> records;
        switch (command) {
            case "kindergarten" -> {
                kind = InstitutionKind.KINDERGARTEN;
                source = KindergartenOpenDataParser.SOURCE;
                String timing = options.get("timing");
                if (timing == null || !timing.matches("\\d{5}")) {
                    throw new IllegalArgumentException("--timing 공시 차수(예: 20261)를 주세요.");
                }
                reference = options.getOrDefault("reference",
                        "유치원알리미 " + timing.substring(0, 4) + "년 " + timing.substring(4) + "차 공시");
                byte[] json = options.containsKey("file")
                        ? Files.readAllBytes(Path.of(options.get("file")))
                        : downloadKindergarten(timing);
                try (InputStream in = new ByteArrayInputStream(json)) {
                    records = KindergartenOpenDataParser.parse(in);
                }
            }
            case "daycare" -> {
                kind = InstitutionKind.DAYCARE;
                source = ChildcareApiParser.SOURCE;
                reference = options.getOrDefault("reference",
                        "어린이집정보공개포털 " + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
                records = options.containsKey("xml-dir")
                        ? readDaycareDir(Path.of(options.get("xml-dir")))
                        : downloadDaycare(readArcodes(options.get("arcodes-file")), options.get("save-dir"));
            }
            default -> {
                usage();
                System.exit(2);
                return;
            }
        }

        long active = records.stream().filter(InstitutionRecord::active).count();
        System.out.printf("parsed %d %s rows (%d active), reference=%s%n", records.size(), kind, active, reference);
        printRegionSummary(records);
        if (dryRun) {
            System.out.println("dry-run: DB에 넣지 않았어요.");
            return;
        }
        String url = requireEnv("SUPABASE_DB_URL");
        try (Connection connection = DriverManager.getConnection(
                url, requireEnv("SUPABASE_DB_USERNAME"), requireEnv("SUPABASE_DB_PASSWORD"))) {
            PublicInstitutionImporter.Result result = PublicInstitutionImporter.importRecords(
                    connection, kind, source, records, reference, batch, closeMissing);
            System.out.printf("upserted %d, closed %d (batch %s)%n", result.upserted(), result.closed(), batch);
        }
    }

    static Map<String, String> parseOptions(String[] argv) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < argv.length; i++) {
            String arg = argv[i];
            if (!arg.startsWith("--")) throw new IllegalArgumentException("알 수 없는 인자: " + arg);
            String name = arg.substring(2);
            if (name.equals("dry-run") || name.equals("close-missing")) {
                options.put(name, "true");
            } else {
                if (i + 1 >= argv.length) throw new IllegalArgumentException(arg + " 뒤에 값이 필요해요.");
                options.put(name, argv[++i]);
            }
        }
        return options;
    }

    static byte[] downloadKindergarten(String timing) throws IOException, InterruptedException {
        String query = form(Map.of(
                "combineSidoName", "전체 시/도",
                "combineSidoCode", "99",
                "timingListCode", timing,
                "gongsiListCode", "05", // 일반 현황
                "ExcelCsv", "3")); // JSON
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(KINDERGARTEN_DOWNLOAD + "?" + query))
                        .timeout(Duration.ofMinutes(5)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("유치원알리미 다운로드 실패: HTTP " + response.statusCode());
        }
        System.out.printf("downloaded %,d bytes from 유치원알리미 (%s)%n", response.body().length, timing);
        return response.body();
    }

    static List<String> readArcodes(String file) throws IOException {
        if (file == null) throw new IllegalArgumentException("--arcodes-file(시군구 코드 5자리, 한 줄에 하나)을 주세요.");
        Set<String> codes = new LinkedHashSet<>();
        for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
            String code = line.strip();
            if (code.isEmpty() || code.startsWith("#")) continue;
            if (!code.matches("\\d{5}")) throw new IllegalArgumentException("시군구 코드가 5자리 숫자가 아니에요: " + code);
            codes.add(code);
        }
        return List.copyOf(codes);
    }

    static List<InstitutionRecord> downloadDaycare(Collection<String> arcodes, String saveDir)
            throws IOException, InterruptedException {
        String key = requireEnv("CHILDCARE_API_KEY");
        Path save = saveDir == null ? null : Files.createDirectories(Path.of(saveDir));
        List<InstitutionRecord> records = new ArrayList<>();
        for (String arcode : arcodes) {
            String query = form(Map.of("key", key, "arcode", arcode));
            HttpResponse<byte[]> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(CHILDCARE_API + "?" + query))
                            .timeout(Duration.ofMinutes(1)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("어린이집 API 실패: arcode=" + arcode + " HTTP " + response.statusCode());
            }
            if (save != null) Files.write(save.resolve(arcode + ".xml"), response.body());
            try (InputStream in = new ByteArrayInputStream(response.body())) {
                List<InstitutionRecord> page = ChildcareApiParser.parse(in);
                System.out.printf("arcode %s: %d%n", arcode, page.size());
                records.addAll(page);
            }
            Thread.sleep(200); // 원천 서버에 몰아 보내지 않는다
        }
        return records;
    }

    static List<InstitutionRecord> readDaycareDir(Path dir) throws IOException {
        List<InstitutionRecord> records = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    records.addAll(ChildcareApiParser.parse(in));
                }
            }
        }
        return records;
    }

    private static void printRegionSummary(List<InstitutionRecord> records) {
        Map<String, Integer> bySido = new java.util.TreeMap<>();
        for (InstitutionRecord record : records) {
            bySido.merge(record.sido() == null ? "(시도 없음)" : record.sido(), 1, Integer::sum);
        }
        bySido.forEach((sido, count) -> System.out.printf("  %s %d%n", sido, count));
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " 환경 변수가 필요해요.");
        return value;
    }

    private static void usage() {
        System.err.println("""
                사용법:
                  kindergarten --timing 20261 [--file kinder.json] [--reference "..."] [--dry-run] [--close-missing]
                  daycare (--arcodes-file codes.txt [--save-dir dir] | --xml-dir dir) [--reference "..."] [--dry-run] [--close-missing]
                """);
    }
}
