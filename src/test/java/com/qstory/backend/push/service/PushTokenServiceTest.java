package com.qstory.backend.push.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.qstory.backend.push.PushPlatform;
import com.qstory.backend.push.repository.PushDeviceTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 등록은 token unique에 기댄 upsert 한 문장이다 - 다른 계정 것이던 토큰을 호출자로 옮기고 disabled_at을 비우는 것은
 * PushDeviceTokenRepository.upsert의 SQL이 한다(로컬 postgres에서 확인). 여기서는 호출자 id로만 부르는지 본다.
 */
class PushTokenServiceTest {

    private final PushDeviceTokenRepository repository = mock(PushDeviceTokenRepository.class);
    private final PushTokenService service = new PushTokenService(repository);

    @Test
    void registerUpsertsForTheCaller() {
        UUID caller = UUID.randomUUID();

        service.register(caller, "fcm:abc", PushPlatform.IOS);

        verify(repository).upsert(any(UUID.class), eq(caller), eq("fcm:abc"), eq("IOS"), any(Instant.class));
    }

    @Test
    void removeOnlyTouchesTheCallersRow() {
        UUID caller = UUID.randomUUID();

        service.remove(caller, "fcm:abc");

        verify(repository).deleteByUserIdAndToken(caller, "fcm:abc");
    }
}
