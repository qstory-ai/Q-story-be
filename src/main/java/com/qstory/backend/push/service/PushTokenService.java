package com.qstory.backend.push.service;

import com.qstory.backend.push.PushPlatform;
import com.qstory.backend.push.repository.PushDeviceTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 앱이 올린 기기 토큰의 등록·해제(POST /v1/me/push-tokens, /remove). 입력 형식 검사는 컨트롤러가 한다. */
@Service
public class PushTokenService {

    private static final Logger log = LoggerFactory.getLogger(PushTokenService.class);

    private final PushDeviceTokenRepository repository;

    public PushTokenService(PushDeviceTokenRepository repository) {
        this.repository = repository;
    }

    /** 같은 토큰이 다른 계정 것이었으면(같은 기기, 다른 로그인) 호출자로 옮긴다. 비활성화는 풀린다. */
    public void register(UUID userId, String token, PushPlatform platform) {
        repository.upsert(UUID.randomUUID(), userId, token, platform.name(), Instant.now());
        log.info("push-token.registered userId={} platform={} tokenHash={}", userId, platform, FcmClient.tokenHash(token));
    }

    /** 호출자 것일 때만 지운다. 없거나 이미 다른 계정으로 옮겨 간 토큰이어도 조용히 끝낸다(로그아웃이 실패하면 안 된다). */
    public void remove(UUID userId, String token) {
        int removed = repository.deleteByUserIdAndToken(userId, token);
        log.info("push-token.removed userId={} removed={} tokenHash={}", userId, removed, FcmClient.tokenHash(token));
    }
}
