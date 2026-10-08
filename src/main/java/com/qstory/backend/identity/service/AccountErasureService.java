package com.qstory.backend.identity.service;

import com.qstory.backend.bookmark.repository.BookmarkRepository;
import com.qstory.backend.common.util.StorageDeletionRetry;
import com.qstory.backend.common.util.SupabaseStorageClient;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.conversationrecord.repository.ConversationRecordRepository;
import com.qstory.backend.feedback.repository.ImprovementFeedbackRepository;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.repository.PasswordResetTokenRepository;
import com.qstory.backend.interaction.service.InteractionService;
import com.qstory.backend.notification.repository.NotificationRepository;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.repository.ClassHomeroomHistoryRepository;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.tutor.service.OrganizationTutorService;
import com.qstory.backend.parent.child.repository.ChildRepository;
import com.qstory.backend.parent.notification.repository.NotificationSettingsRepository;
import com.qstory.backend.push.repository.PushDeviceTokenRepository;
import com.qstory.backend.recordingconsent.service.RecordingConsentService;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.TutorStudentStatus;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 회원 탈퇴의 실제 삭제와 익명화. 결제 기록(payment_order)과 탈퇴 사유·동의 이력(user_consent)이 이 계정
 * 행을 참조하므로 행은 지우지 않고 식별 정보를 비운다. 행이 남아 FK의 on delete cascade가 돌지 않으니
 * 딸린 데이터는 여기서 직접 지운다.
 *
 * <ul>
 *   <li>공통: 비밀번호 재설정 토큰, 북마크, 알림, 알림 설정, 앱 푸시 기기 토큰, 개선 의견, 이 계정의 대화 원장, 프로필 사진, 화면 녹화(이어진
 *   베타 세션 포함)·화면 녹화 동의, 화면 상호작용.</li>
 *   <li>보호자: 가정 세션 기록 삭제, 지난 반 수업 열람 권한 해제(참여 명단은 유지), 반 명단 연결 해제, 아이 프로필 마스킹(가입 기록만 남김).</li>
 *   <li>선생님: 기관 반은 담임만 비우고(반·수업·학생·기록 유지), 기관 밖 자기 반·수업·학생·기록은 지운다.</li>
 *   <li>관리자: 기관과 기관 데이터는 그대로, 계정만 익명화.</li>
 * </ul>
 *
 * <p>보존: payment_order(5년), account_deletion_feedback, user_consent, 기관 반 수업 기록.
 */
@Service
public class AccountErasureService {

    private static final Logger log = LoggerFactory.getLogger(AccountErasureService.class);

    static final String DELETED_DISPLAY_NAME = "탈퇴한 사용자";
    static final String MASKED_CHILD_NAME = "삭제됨";
    /** fe entities/child/model/avatars.ts CHILD_AVATARS[0].key - TutorStudentService의 기본값과 같다. */
    static final String DEFAULT_CHILD_AVATAR_KEY = "fox";

    private final AppUserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final BookmarkRepository bookmarkRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationSettingsRepository notificationSettingsRepository;
    private final ImprovementFeedbackRepository improvementFeedbackRepository;
    private final ConversationRecordRepository conversationRecordRepository;
    private final StoryCompletionRepository storyCompletionRepository;
    private final ChildRepository childRepository;
    private final TutorStudentRepository tutorStudentRepository;
    private final LessonRepository lessonRepository;
    private final ClassGroupRepository classGroupRepository;
    private final ClassHomeroomHistoryRepository homeroomHistoryRepository;
    private final OrganizationTutorRepository organizationTutorRepository;
    private final OrganizationTutorService organizationTutorService;
    private final SupabaseStorageClient storageClient;
    private final StorageDeletionRetry storageDeletionRetry;
    private final AppProperties config;
    private final RecordingConsentService recordingConsentService;
    private final InteractionService interactionService;
    private final PushDeviceTokenRepository pushDeviceTokenRepository;

    public AccountErasureService(
            AppUserRepository userRepository, PasswordResetTokenRepository passwordResetTokenRepository,
            BookmarkRepository bookmarkRepository, NotificationRepository notificationRepository,
            NotificationSettingsRepository notificationSettingsRepository,
            ImprovementFeedbackRepository improvementFeedbackRepository,
            ConversationRecordRepository conversationRecordRepository,
            StoryCompletionRepository storyCompletionRepository, ChildRepository childRepository,
            TutorStudentRepository tutorStudentRepository, LessonRepository lessonRepository,
            ClassGroupRepository classGroupRepository, ClassHomeroomHistoryRepository homeroomHistoryRepository,
            OrganizationTutorRepository organizationTutorRepository,
            OrganizationTutorService organizationTutorService, SupabaseStorageClient storageClient,
            StorageDeletionRetry storageDeletionRetry, AppProperties config,
            RecordingConsentService recordingConsentService, InteractionService interactionService,
            PushDeviceTokenRepository pushDeviceTokenRepository) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.bookmarkRepository = bookmarkRepository;
        this.notificationRepository = notificationRepository;
        this.notificationSettingsRepository = notificationSettingsRepository;
        this.improvementFeedbackRepository = improvementFeedbackRepository;
        this.conversationRecordRepository = conversationRecordRepository;
        this.storyCompletionRepository = storyCompletionRepository;
        this.childRepository = childRepository;
        this.tutorStudentRepository = tutorStudentRepository;
        this.lessonRepository = lessonRepository;
        this.classGroupRepository = classGroupRepository;
        this.homeroomHistoryRepository = homeroomHistoryRepository;
        this.organizationTutorRepository = organizationTutorRepository;
        this.organizationTutorService = organizationTutorService;
        this.storageClient = storageClient;
        this.storageDeletionRetry = storageDeletionRetry;
        this.config = config;
        this.recordingConsentService = recordingConsentService;
        this.interactionService = interactionService;
        this.pushDeviceTokenRepository = pushDeviceTokenRepository;
    }

    /** 한 트랜잭션으로 지우고 익명화한다. 음성 연구 녹음 철회는 호출자(AuthService)가 먼저 별도 트랜잭션으로 한다. */
    @Transactional
    public void erase(AppUser user) {
        UUID userId = user.getId();
        Instant now = Instant.now();

        // 회차(play_session)로 이어진 베타 세션도 찾아 지우므로, 회차를 지우는 역할별 정리보다 먼저 한다.
        recordingConsentService.eraseForAccount(userId);
        interactionService.deleteAllByUserId(userId);

        if (user.getRole() == Role.PARENT) {
            eraseParentData(user, now);
        } else if (user.getRole() == Role.TUTOR) {
            eraseTutorData(userId);
        }

        passwordResetTokenRepository.deleteAllByUserId(userId);
        bookmarkRepository.deleteAllByUserId(userId);
        notificationRepository.deleteAllByUserId(userId);
        notificationSettingsRepository.deleteAllByUserId(userId);
        // 탈퇴한 계정의 기기로 푸시가 가지 않게 한다(075).
        pushDeviceTokenRepository.deleteAllByUserId(userId);
        improvementFeedbackRepository.deleteAllByUserId(userId);
        conversationRecordRepository.deleteAllByUserId(userId);

        String profileObject = user.getProfileImageObjectName();
        user.setProfileImageObjectName(null);
        user.setProfileImageUrl(null);
        anonymize(user, now);
        userRepository.save(user);
        deleteProfileImageAfterCommit(userId, profileObject);
    }

    /**
     * 이번 배포 전에 탈퇴한 계정을 같은 방식으로 지우고 익명화한다(LegacyDeletedAccountCleanupService가 계정마다
     * 부른다). 탈퇴 시각은 원래 값을 남긴다. 탈퇴하지 않은 계정이면 아무것도 바꾸지 않고 예외를 던진다.
     */
    @Transactional
    public void eraseLegacyDeleted(UUID userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("account not found: " + userId));
        Instant originalDeletedAt = user.getDeletedAt();
        if (originalDeletedAt == null) {
            throw new IllegalStateException("account is not deleted: " + userId);
        }
        erase(user);
        user.setDeletedAt(originalDeletedAt);
        userRepository.save(user);
    }

    private void eraseParentData(AppUser parent, Instant now) {
        UUID parentId = parent.getId();
        // 반 명단 연결 해제 - 그대로 두면 탈퇴 계정이 학생을 영구히 점유해 다른 보호자의 수락이 409로 막힌다.
        for (TutorStudent student : tutorStudentRepository.findByLinkedParentUser_Id(parentId)) {
            student.setLinkedParentUser(null);
            student.setLinkedAt(null);
            student.setChild(null);
            student.setStatus(TutorStudentStatus.PENDING_PARENT);
            tutorStudentRepository.save(student);
        }
        storyCompletionRepository.deleteHomeSessionsOf(parentId);
        storyCompletionRepository.clearParentAccessOf(parentId);
        childRepository.maskAllOfParent(parentId, MASKED_CHILD_NAME, DEFAULT_CHILD_AVATAR_KEY, now);
    }

    private void eraseTutorData(UUID tutorId) {
        // 기관 반: 학생은 반에 남기고 담임만 비운다(기관 명단·수업·기록은 기관 것).
        organizationTutorRepository.findByTutor_IdOrderByJoinedAtAsc(tutorId)
                .forEach(link -> organizationTutorService.detachTutor(link.getOrganization().getId(), tutorId));
        organizationTutorRepository.deleteByTutor_Id(tutorId);
        // 기관 밖 자기 반·수업·학생·기록. 자식 → 부모 순서로 지운다 - 학생은 반을 지우기 전에(반 id로 찾는다).
        storyCompletionRepository.deletePersonalSessionsOfTutor(tutorId);
        lessonRepository.deletePersonalLessonsOf(tutorId);
        tutorStudentRepository.deleteStudentsOfPersonalClasses(tutorId);
        homeroomHistoryRepository.deletePersonalHistoryOf(tutorId);
        classGroupRepository.deletePersonalClassesOf(tutorId);
    }

    /**
     * login_id(unique)와 (oauth_provider, oauth_subject)(부분 unique)를 원래 값 없이 바꿔 같은 아이디·이메일·
     * 소셜 계정으로 다시 가입할 수 있게 한다. id·role·created_at·organization은 결제·기관 기록 연결을 위해 남긴다.
     */
    private void anonymize(AppUser user, Instant now) {
        String token = UUID.randomUUID().toString();
        user.setLoginId("deleted:" + token);
        user.setEmail("deleted+" + token + "@deleted.invalid");
        user.setDisplayName(DELETED_DISPLAY_NAME);
        user.setPasswordHash(null);
        user.setOauthProvider(null);
        user.setOauthSubject(null);
        user.setChildName(null);
        user.setDeletedAt(now);
    }

    /**
     * 저장소 삭제는 되돌릴 수 없으니 커밋 뒤에 한다. 실패해도 컬럼은 이미 비어 있어 다시 노출되지 않지만, 파일은 지워야 하므로
     * 다시 시도 목록에 넣는다(StorageDeletionRetry가 한 시간마다 다시 지운다).
     */
    private void deleteProfileImageAfterCommit(UUID userId, String objectName) {
        if (objectName == null || !objectName.startsWith("profiles/" + userId + "/")) {
            return;
        }
        AppProperties.Supabase supabase = config.supabase();
        if (supabase == null || !supabase.configured() || supabase.profileImageBucket() == null
                || supabase.profileImageBucket().isBlank()) {
            log.warn("account-delete.profile-image-skipped userId={} reason=storage-not-configured", userId);
            return;
        }
        Runnable delete = () -> {
            if (!storageClient.delete(supabase.profileImageBucket(), objectName)) {
                log.warn("account-delete.profile-image-delete-failed userId={} object={} retry=queued", userId, objectName);
                storageDeletionRetry.enqueue(supabase.profileImageBucket(), objectName);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }
}
