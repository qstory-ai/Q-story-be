package com.qstory.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * db/schema 파일 번호가 겹치면 Flyway가 "Found more than one migration with version"으로 앱을 띄우지 못한다.
 * 단위 테스트는 DB 없이 돌아서 Flyway를 실행하지 않으므로, 번호 겹침만 여기서 따로 막는다.
 */
class SchemaMigrationVersionTest {

    private static final Path SCHEMA_DIR = Path.of("src/main/resources/db/schema");
    private static final Pattern FILE = Pattern.compile("(\\d+)-.+\\.sql");

    @Test
    void migrationVersionsAreUnique() throws IOException {
        List<String> names;
        try (Stream<Path> files = Files.list(SCHEMA_DIR)) {
            names = files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".sql")).toList();
        }
        assertTrue(!names.isEmpty(), "db/schema에 sql 파일이 없다");

        Map<Integer, List<String>> byVersion = new TreeMap<>();
        for (String name : names) {
            Matcher matcher = FILE.matcher(name);
            assertTrue(matcher.matches(), "NNN-설명.sql 형식이 아니다: " + name);
            byVersion.computeIfAbsent(Integer.parseInt(matcher.group(1)), key -> new java.util.ArrayList<>()).add(name);
        }
        String duplicates = byVersion.values().stream()
                .filter(group -> group.size() > 1)
                .map(group -> String.join(", ", group))
                .collect(Collectors.joining(" / "));
        assertEquals("", duplicates, "같은 번호의 마이그레이션이 있다. 뒤에 들어온 파일 번호를 올린다");
    }
}
