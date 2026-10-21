package com.qstory.backend.realtimeimage.service;

import com.qstory.backend.common.enums.RealtimeImageKind;
import com.qstory.backend.common.enums.RealtimeImageStatus;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.config.RealtimeImageProperties;
import com.qstory.backend.provider.higgsfield.util.HiggsfieldImageClient;
import com.qstory.backend.common.util.SupabaseStorageClient;
import com.qstory.backend.realtimeimage.entity.RealtimeImage;
import com.qstory.backend.realtimeimage.repository.RealtimeImageRepository;
import com.qstory.backend.story.entity.Story;
import com.qstory.backend.story.entity.StoryVisualReferencePack;
import com.qstory.backend.story.repository.StoryRepository;
import com.qstory.backend.story.repository.StoryVisualReferencePackRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 실시간 삽화(Higgsfield) 한 장의 생명주기와 상한을 맡는다. 새 선택지 그림(LiveBranchExecutionWorker)과 시도 장면 막힘 그림
 * (CompanionChatPipelineService)이 모두 여기로 온다.
 *
 * <p>순서: {@link #reserve} 로 질문 지점·세션당 상한(기본 3장) 안에서 자리를 잡고(QUEUED 행) → {@link #generate} 가 Higgsfield에
 * 제출·대기·내려받기 → Supabase story 이미지 버킷에 올리고 READY. 어디서든 실패하면 FAILED로 남기고 null - 호출자는 그림 없이
 * 진행한다(새 선택지는 기존 행동으로 채우고, 시도 장면은 미리 만든 그림 그대로).
 *
 * <p>인물 참고 그림은 정적 삽화와 같은 시트 5장이다. 버킷의 {@code <slug>/references/<인물>-sheet.png} 규칙으로 찾고
 * (fe/scripts/illustrations/README.md), 없는 파일은 한 번 HEAD로 확인해 뺀다.
 */
@Service
public class RealtimeImageService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeImageService.class);

    /** 인물 라벨 → 시트 파일 이름 앞부분. 할머니와 마녀는 같은 사람이라 시트도 하나다. */
    private static final Map<String, String> SHEET_FILES = Map.of(
            "GRETEL", "gretel-sheet", "HANSEL", "hansel-sheet", "FATHER", "father-sheet",
            "STEPMOTHER", "stepmother-sheet", "OLD_WOMAN", "witch-sheet", "WITCH", "witch-sheet");

    public record Generated(String imageUrl, String bucket, String objectName, String mimeType, byte[] bytes) {}

    private final RealtimeImageProperties properties;
    private final RealtimeImageRepository repository;
    private final HiggsfieldImageClient client;
    private final SupabaseStorageClient storageClient;
    private final AppProperties config;
    private final StoryVisualReferencePackRepository referencePackRepository;
    private final StoryRepository storyRepository;
    private final HttpClient httpClient;
    private final Executor executor;
    private final Map<String, Boolean> sheetExists = new ConcurrentHashMap<>();

    public RealtimeImageService(
            RealtimeImageProperties properties, RealtimeImageRepository repository, HiggsfieldImageClient client,
            SupabaseStorageClient storageClient, AppProperties config,
            StoryVisualReferencePackRepository referencePackRepository, StoryRepository storyRepository,
            HttpClient httpClient, @Qualifier("realtimeImageExecutor") Executor executor) {
        this.properties = properties;
        this.repository = repository;
        this.client = client;
        this.storageClient = storageClient;
        this.config = config;
        this.referencePackRepository = referencePackRepository;
        this.storyRepository = storyRepository;
        this.httpClient = httpClient;
        this.executor = executor;
    }

    /** Higgsfield 키와 Supabase가 모두 있을 때만. 아니면 호출자는 예전 길(OpenRouter 이미지 / 미리 만든 그림)로 간다. */
    public boolean enabled() {
        return properties.configured() && config.supabase() != null && config.supabase().configured();
    }

    public int maxPerAnchorSession() {
        return properties.maxPerAnchorSession();
    }

    /**
     * 상한 안이면 자리를 잡고 QUEUED 행을 돌려준다. 세션이 없거나(익명 구버전 클라이언트) 상한에 닿았으면 null.
     * 자리를 먼저 잡기 때문에 같은 세션의 병렬 요청(새 선택지 3개)도 상한을 넘기지 않는다 - 조회와 저장 사이의 아주 짧은 경쟁은
     * 많아야 1장 초과라 허용한다.
     */
    public RealtimeImage reserve(UUID sessionId, String storyId, String anchorId, RealtimeImageKind kind) {
        if (!enabled() || sessionId == null || anchorId == null || storyId == null) {
            return null;
        }
        long used = repository.countBySessionIdAndAnchorIdAndStatusNot(sessionId, anchorId, RealtimeImageStatus.FAILED);
        if (used >= properties.maxPerAnchorSession()) {
            log.info("realtime-image.cap-reached storyId={} anchorId={} sessionId={} used={} cap={} kind={}",
                    storyId, anchorId, sessionId, used, properties.maxPerAnchorSession(), kind);
            return null;
        }
        Instant now = Instant.now();
        return repository.save(RealtimeImage.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .storyId(storyId)
                .anchorId(anchorId)
                .kind(kind)
                .status(RealtimeImageStatus.QUEUED)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /** 자리를 잡아 둔 행 하나를 실제로 만든다(동기 - 보통 20~50초). 실패하면 FAILED로 남기고 null. */
    public Generated generate(RealtimeImage reservation, String prompt, List<String> referenceUrls) {
        long startedAt = System.currentTimeMillis();
        reservation.setPromptHash(sha256(prompt).substring(0, 16));
        update(reservation, RealtimeImageStatus.GENERATING, null);
        try {
            HiggsfieldImageClient.Image image = client.generate(
                    prompt, referenceUrls, sha256(reservation.getId() + "|" + prompt), properties.pollTimeoutMs());
            String bucket = config.supabase().storyImageBucket();
            String objectName = "realtime/" + reservation.getKind().name().toLowerCase(Locale.ROOT) + "/"
                    + reservation.getId() + "." + extension(image.mimeType());
            if (!storageClient.upload(bucket, objectName, image.bytes(), image.mimeType())) {
                storageClient.delete(bucket, objectName);
                update(reservation, RealtimeImageStatus.FAILED, "UPLOAD_FAILED");
                return null;
            }
            String url = storageClient.publicObjectUrl(bucket, objectName);
            reservation.setProviderRequestId(image.requestId());
            reservation.setBucket(bucket);
            reservation.setObjectName(objectName);
            reservation.setImageUrl(url);
            update(reservation, RealtimeImageStatus.READY, null);
            log.info("realtime-image.ready id={} kind={} anchorId={} ms={}", reservation.getId(), reservation.getKind(),
                    reservation.getAnchorId(), System.currentTimeMillis() - startedAt);
            return new Generated(url, bucket, objectName, image.mimeType(), image.bytes());
        } catch (HiggsfieldImageClient.HiggsfieldException error) {
            log.warn("realtime-image.failed id={} kind={} code={} ms={}", reservation.getId(), reservation.getKind(),
                    error.code(), System.currentTimeMillis() - startedAt);
            update(reservation, RealtimeImageStatus.FAILED, error.code());
            return null;
        } catch (Exception error) {
            log.warn("realtime-image.failed id={} kind={}", reservation.getId(), reservation.getKind(), error);
            update(reservation, RealtimeImageStatus.FAILED, "GENERATION_FAILED");
            return null;
        }
    }

    /** 시도 장면처럼 답을 기다리게 할 수 없는 자리 - 따로 만들고 프런트가 GET /v1/realtime-images/{id}로 지켜본다. */
    public void generateAsync(RealtimeImage reservation, String prompt, List<String> referenceUrls) {
        executor.execute(() -> generate(reservation, prompt, referenceUrls));
    }

    public Optional<RealtimeImage> find(UUID id) {
        return repository.findById(id);
    }

    /** "HG-SPK-GRETEL" → "GRETEL". 이미 라벨이면 그대로. 순서를 지키고 중복은 뺀다. */
    public static List<String> normalizeLabels(Collection<String> speakerOrLabels) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (String value : speakerOrLabels) {
            if (value == null) continue;
            int index = value.indexOf("-SPK-");
            labels.add((index >= 0 ? value.substring(index + 5) : value).toUpperCase(Locale.ROOT));
        }
        return new ArrayList<>(labels);
    }

    /** 인물 시트 공개 URL(버킷에 있는 것만). 같은 시트(할머니=마녀)는 한 번만. */
    public List<String> referenceSheetUrls(String storyId, Collection<String> labels) {
        String slug = storyRepository.findById(storyId).map(Story::getSlug).orElse(null);
        if (slug == null || config.supabase() == null) {
            return List.of();
        }
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (String label : labels) {
            String file = SHEET_FILES.get(label);
            if (file == null) continue;
            String url = storageClient.publicObjectUrl(config.supabase().storyImageBucket(), slug + "/references/" + file + ".png");
            if (sheetExists.computeIfAbsent(url, this::exists)) {
                urls.add(url);
            }
        }
        return new ArrayList<>(urls);
    }

    /** story_visual_reference_pack의 인물 외형 사실(임포트된 visual-generation-contract) - 라벨별. */
    public Map<String, List<String>> characterFacts(String storyId, Collection<String> labels) {
        Map<String, List<String>> byLabel = new LinkedHashMap<>();
        if (labels.isEmpty()) return byLabel;
        for (StoryVisualReferencePack pack : referencePackRepository.findByStory_IdAndLabelIn(storyId, labels)) {
            byLabel.put(pack.getLabel(), pack.getImmutableFacts() == null ? List.of() : pack.getImmutableFacts());
        }
        return byLabel;
    }

    private boolean exists(String url) {
        try {
            HttpResponse<Void> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            boolean ok = response.statusCode() / 100 == 2;
            if (!ok) {
                log.warn("realtime-image.sheet-missing url={} status={}", url, response.statusCode());
            }
            return ok;
        } catch (Exception error) {
            log.warn("realtime-image.sheet-check-failed url={} cause={}", url, error.toString());
            return false;
        }
    }

    private void update(RealtimeImage image, RealtimeImageStatus status, String errorCode) {
        image.setStatus(status);
        image.setErrorCode(errorCode);
        image.setUpdatedAt(Instant.now());
        repository.save(image);
    }

    private static String extension(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            default -> "webp";
        };
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
