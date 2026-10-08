package com.qstory.backend.push.service;

import com.qstory.backend.push.repository.PushDeviceTokenRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 저장된 인앱 알림을 그 계정의 살아 있는 기기 토큰으로 푸시한다. NotificationPublisher가 알림을 실제로 저장했을 때만
 * (설정·중복 검사 통과) 부른다. 트랜잭션이 커밋된 뒤에 pushExecutor로 넘겨 요청 스레드는 FCM 응답을 기다리지 않고,
 * 롤백된 알림은 푸시되지 않는다. FCM이 꺼져 있으면(서비스 계정 키 없음) 아무것도 하지 않는다.
 */
@Component
public class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private final FcmClient fcmClient;
    private final PushDeviceTokenRepository tokenRepository;
    private final Executor executor;

    public PushDispatcher(
            FcmClient fcmClient, PushDeviceTokenRepository tokenRepository,
            @Qualifier("pushExecutor") Executor executor) {
        this.fcmClient = fcmClient;
        this.tokenRepository = tokenRepository;
        this.executor = executor;
    }

    public void dispatchAfterCommit(UUID userId, PushMessage message) {
        if (!fcmClient.enabled()) {
            return;
        }
        Runnable submit = () -> submit(userId, message);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    private void submit(UUID userId, PushMessage message) {
        try {
            executor.execute(() -> deliver(userId, message));
        } catch (RejectedExecutionException full) {
            // 대기열이 찼거나 종료 중 - 인앱 알림은 이미 저장됐으니 푸시 한 건만 놓친다.
            log.warn("fcm.send-failed status=0 reason=queue-full disabled=false kind={}", message.kind());
        }
    }

    /** 토큰마다 한 번씩 보낸다. 죽은 토큰은 비활성화해 다음부터 보내지 않는다. */
    void deliver(UUID userId, PushMessage message) {
        try {
            List<String> tokens = tokenRepository.findActiveTokens(userId);
            if (tokens.isEmpty()) {
                log.debug("fcm.no-tokens userId={} kind={}", userId, message.kind());
                return;
            }
            int sent = 0;
            int disabled = 0;
            for (String token : tokens) {
                FcmSendResult result = fcmClient.send(token, message);
                if (result.outcome() == FcmSendResult.Outcome.SENT) {
                    sent++;
                } else if (result.outcome() == FcmSendResult.Outcome.TOKEN_INVALID) {
                    disabled += tokenRepository.disable(token, Instant.now());
                }
            }
            log.info("fcm.sent count={} tokens={} disabled={} kind={} notificationId={}",
                    sent, tokens.size(), disabled, message.kind(), message.notificationId());
        } catch (RuntimeException error) {
            log.warn("fcm.send-failed status=0 reason=dispatch-error disabled=false kind={} error={}",
                    message.kind(), error.getClass().getSimpleName());
        }
    }
}
