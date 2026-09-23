package com.qstory.backend.story.service;

import com.qstory.backend.common.enums.AssetCategory;
import com.qstory.backend.common.util.SupabaseStorageClient;
import com.qstory.backend.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * 스토리 정적 자산(삽화/내레이션)이 앱에 내려갈 때의 URL을 한 곳에서 만든다. 파일은 Supabase 공개
 * 버킷에 {@code <slug>/<file>} 이름으로 올라가 있고(fe scripts/upload-story-assets-to-supabase.mjs,
 * 버킷 분류는 fe content/registry.yaml assetStorage와 같다), DB에는 "illustrations/x.jpg" 같은
 * 스토리지 상대 경로만 남는다 - 프로젝트 URL이나 버킷 이름이 바뀌어도 재임포트할 필요가 없다.
 *
 * <p>Supabase가 설정되지 않은 로컬 실행에서만 예전 방식(프론트엔드 public/ 루트의
 * {@code /story/<slug>/<file>})으로 떨어진다 - 없는 URL을 만들어 내지 않기 위해서다.
 */
@Component
public class StoryAssetUrls {

    private static final String LEGACY_PUBLIC_PREFIX = "/story/";

    private final AppProperties config;
    private final SupabaseStorageClient storageClient;

    public StoryAssetUrls(AppProperties config, SupabaseStorageClient storageClient) {
        this.config = config;
        this.storageClient = storageClient;
    }

    /** 임포트된 자산 한 건의 URL. 이미 절대 URL인 것(재렌더링 내레이션, 실시간 분기 삽화)은 그대로. */
    public String forAsset(String storySlug, AssetCategory category, String file) {
        if (isAbsolute(file)) return file;
        if (!config.supabase().configured()) return LEGACY_PUBLIC_PREFIX + storySlug + "/" + file;
        return storageClient.publicObjectUrl(bucketFor(category), storySlug + "/" + file);
    }

    /**
     * 카탈로그 커버 이미지. story.cover_image_url은 db/schema/006 시절에 프론트엔드 public/ 경로
     * ({@code /story/<slug>/illustrations/x.jpg})로 적혔으므로 그 접두사를 벗겨 삽화 버킷 URL로
     * 바꾼다. 절대 URL이거나 알 수 없는 형태면 손대지 않는다.
     */
    public String forCover(String storySlug, String coverImageUrl) {
        if (coverImageUrl == null || coverImageUrl.isBlank() || isAbsolute(coverImageUrl)) return coverImageUrl;
        String legacyPrefix = LEGACY_PUBLIC_PREFIX + storySlug + "/";
        if (coverImageUrl.startsWith(legacyPrefix)) {
            return forAsset(storySlug, AssetCategory.SCENE_ART, coverImageUrl.substring(legacyPrefix.length()));
        }
        return coverImageUrl;
    }

    private String bucketFor(AssetCategory category) {
        return switch (category) {
            case SCENE_ART, BRANCH_ART -> config.supabase().storyImageBucket();
            case NARRATION, BRIDGE -> config.supabase().storyAudioBucket();
        };
    }

    private static boolean isAbsolute(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }
}
