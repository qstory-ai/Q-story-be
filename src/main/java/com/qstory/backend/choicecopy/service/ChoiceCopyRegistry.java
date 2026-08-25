package com.qstory.backend.choicecopy.service;
import com.qstory.backend.choicecopy.ChoiceCopyVariant;
import com.qstory.backend.choicecopy.repository.ChoiceCopyRepository;

import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 검수자가 승인한 THREE_PATHS 카피 뱅크(copy bank)의 인메모리 캐시. 부팅 시 한 번, 그리고 매 POST
 * /v1/admin/stories/import 이후에 다시 Postgres로부터 로드된다(StoryImportService 참조) - 이는 라우팅된
 * 질문마다 읽히므로, 요청마다 쿼리되는 일은 결코 없다.
 */
@Component
@Order(1)
public class ChoiceCopyRegistry implements ApplicationRunner {

    private final ChoiceCopyRepository repository;

    private volatile Map<String, List<ChoiceCopyVariant>> copyBank = Map.of();

    public ChoiceCopyRegistry(ChoiceCopyRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    public void reload() {
        copyBank = repository.groupedByFamily();
    }

    public List<ChoiceCopyVariant> variantsFor(String familyId) {
        return copyBank.getOrDefault(familyId, List.of());
    }
}
