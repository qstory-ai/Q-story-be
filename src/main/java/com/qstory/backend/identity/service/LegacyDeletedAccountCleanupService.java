package com.qstory.backend.identity.service;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.voiceresearch.service.VoiceResearchService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 한 번만 돌리는 정리: 실제 삭제·익명화가 생기기 전에 탈퇴한 계정(deleted_at은 있는데 이메일이 익명화 주소가
 * 아닌 계정)에 회원 탈퇴와 같은 삭제를 적용한다. 계정마다 따로 트랜잭션을 쓰고(AccountErasureService 프록시
 * 호출), 한 계정이 실패해도 나머지는 계속한다. 정리된 계정은 이메일이 익명화 주소라 다시 잡히지 않는다.
 *
 * <p>의도적으로 @Transactional이 아니다 - 붙이면 계정별 트랜잭션이 하나로 묶여 한 건의 실패가 전부를 되돌린다.
 */
@Service
public class LegacyDeletedAccountCleanupService {

    private static final Logger log = LoggerFactory.getLogger(LegacyDeletedAccountCleanupService.class);

    public record Result(int found, int erased, int failed) {}

    private final AppUserRepository userRepository;
    private final AccountErasureService accountErasureService;
    private final VoiceResearchService voiceResearchService;

    public LegacyDeletedAccountCleanupService(
            AppUserRepository userRepository, AccountErasureService accountErasureService,
            VoiceResearchService voiceResearchService) {
        this.userRepository = userRepository;
        this.accountErasureService = accountErasureService;
        this.voiceResearchService = voiceResearchService;
    }

    /** dryRun - 정리 대상 계정 수만 센다. */
    public long countPending() {
        return userRepository.countLegacyDeletedAccounts();
    }

    public Result run() {
        List<AppUser> accounts = userRepository.findLegacyDeletedAccounts();
        int erased = 0;
        int failed = 0;
        for (AppUser account : accounts) {
            try {
                if (account.getRole() == Role.PARENT) {
                    withdrawVoiceResearch(account);
                }
                accountErasureService.eraseLegacyDeleted(account.getId());
                erased++;
            } catch (RuntimeException failure) {
                failed++;
                log.warn("legacy-account-erase.failed userId={}", account.getId(), failure);
            }
        }
        log.info("legacy-account-erase.done found={} erased={} failed={}", accounts.size(), erased, failed);
        return new Result(accounts.size(), erased, failed);
    }

    /** 탈퇴 흐름처럼 삭제 트랜잭션보다 먼저, 실패해도 계속한다(남은 녹음은 만료 정리가 이어서 지운다). */
    private void withdrawVoiceResearch(AppUser account) {
        try {
            voiceResearchService.withdrawForDeletedAccount(account.getId());
        } catch (RuntimeException storageFailure) {
            log.warn("legacy-account-erase.voice-research-cleanup-failed userId={}", account.getId(), storageFailure);
        }
    }
}
