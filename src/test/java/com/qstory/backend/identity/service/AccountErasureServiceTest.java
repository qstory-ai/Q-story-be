package com.qstory.backend.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.bookmark.repository.BookmarkRepository;
import com.qstory.backend.common.util.SupabaseStorageClient;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.conversationrecord.repository.ConversationRecordRepository;
import com.qstory.backend.feedback.repository.ImprovementFeedbackRepository;
import com.qstory.backend.identity.OAuthProvider;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.repository.PasswordResetTokenRepository;
import com.qstory.backend.interaction.service.InteractionService;
import com.qstory.backend.notification.repository.NotificationRepository;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.repository.ClassHomeroomHistoryRepository;
import com.qstory.backend.org.tutor.entity.OrganizationTutor;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class AccountErasureServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordResetTokenRepository resetTokens = mock(PasswordResetTokenRepository.class);
    private final BookmarkRepository bookmarks = mock(BookmarkRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final NotificationSettingsRepository notificationSettings = mock(NotificationSettingsRepository.class);
    private final ImprovementFeedbackRepository improvementFeedback = mock(ImprovementFeedbackRepository.class);
    private final ConversationRecordRepository conversationRecords = mock(ConversationRecordRepository.class);
    private final StoryCompletionRepository completions = mock(StoryCompletionRepository.class);
    private final ChildRepository children = mock(ChildRepository.class);
    private final TutorStudentRepository students = mock(TutorStudentRepository.class);
    private final LessonRepository lessons = mock(LessonRepository.class);
    private final ClassGroupRepository classGroups = mock(ClassGroupRepository.class);
    private final ClassHomeroomHistoryRepository homeroomHistory = mock(ClassHomeroomHistoryRepository.class);
    private final OrganizationTutorRepository orgTutors = mock(OrganizationTutorRepository.class);
    private final OrganizationTutorService orgTutorService = mock(OrganizationTutorService.class);
    private final SupabaseStorageClient storage = mock(SupabaseStorageClient.class);
    private final AppProperties config = mock(AppProperties.class);
    private final com.qstory.backend.common.util.StorageDeletionRetry deletionRetry =
            mock(com.qstory.backend.common.util.StorageDeletionRetry.class);
    private final RecordingConsentService recordingConsent = mock(RecordingConsentService.class);
    private final InteractionService interactions = mock(InteractionService.class);
    private final PushDeviceTokenRepository pushTokens = mock(PushDeviceTokenRepository.class);

    private final AccountErasureService service = new AccountErasureService(
            users, resetTokens, bookmarks, notifications, notificationSettings, improvementFeedback,
            conversationRecords, completions, children, students, lessons, classGroups, homeroomHistory,
            orgTutors, orgTutorService, storage, deletionRetry, config, recordingConsent, interactions, pushTokens);

    private AppUser user(Role role) {
        return AppUser.builder()
                .id(UUID.randomUUID())
                .role(role)
                .loginId("mom@example.com")
                .email("mom@example.com")
                .passwordHash("hash")
                .displayName("엄마")
                .childName("하린")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private void verifyCommonCleanup(UUID userId) {
        verify(resetTokens).deleteAllByUserId(userId);
        verify(bookmarks).deleteAllByUserId(userId);
        verify(notifications).deleteAllByUserId(userId);
        verify(notificationSettings).deleteAllByUserId(userId);
        // 075: 계정 행이 남아 FK cascade가 돌지 않으므로 기기 토큰을 직접 지워야 탈퇴 계정으로 푸시가 가지 않는다.
        verify(pushTokens).deleteAllByUserId(userId);
        verify(improvementFeedback).deleteAllByUserId(userId);
        verify(conversationRecords).deleteAllByUserId(userId);
        // 073: 화면 녹화·녹화 동의·상호작용은 user_id만 비우지 않고 지운다.
        verify(recordingConsent).eraseForAccount(userId);
        verify(interactions).deleteAllByUserId(userId);
    }

    @Test
    void eraseIsTransactional() throws Exception {
        assertNotNull(AccountErasureService.class.getMethod("erase", AppUser.class).getAnnotation(Transactional.class));
    }

    @Test
    void accountRowIsAnonymizedSoTheSameEmailAndSocialAccountCanSignUpAgain() {
        AppUser user = user(Role.PARENT);
        user.setOauthProvider(OAuthProvider.GOOGLE);
        user.setOauthSubject("google-sub");
        UUID id = user.getId();
        Instant createdAt = user.getCreatedAt();

        service.erase(user);

        assertEquals(id, user.getId());
        assertEquals(createdAt, user.getCreatedAt());
        assertNotNull(user.getDeletedAt());
        assertEquals("탈퇴한 사용자", user.getDisplayName());
        assertTrue(user.getLoginId().startsWith("deleted:"), user.getLoginId());
        assertTrue(!user.getLoginId().contains("mom@example.com"), "원래 로그인 아이디가 남으면 안 된다");
        assertTrue(user.getEmail().matches("deleted\\+[0-9a-f-]{36}@deleted\\.invalid"), user.getEmail());
        assertNull(user.getPasswordHash());
        assertNull(user.getOauthProvider());
        assertNull(user.getOauthSubject());
        assertNull(user.getChildName());
        verify(users).save(user);
        // login_id unique와 (oauth_provider, oauth_subject) 부분 unique index 모두 원래 값을 비운다 -
        // 같은 아이디·같은 구글 계정으로 새로 가입해도 탈퇴 행과 충돌하지 않는다.
        assertNotEquals("mom@example.com", user.getLoginId());
    }

    @Test
    void parentHomeDataIsDeletedAndChildProfilesMasked() {
        AppUser parent = user(Role.PARENT);
        UUID id = parent.getId();
        TutorStudent linked = TutorStudent.builder().id(UUID.randomUUID()).linkedParentUser(parent)
                .status(TutorStudentStatus.CONFIRMED).build();
        when(students.findByLinkedParentUser_Id(id)).thenReturn(List.of(linked));

        service.erase(parent);

        verifyCommonCleanup(id);
        verify(completions).deleteHomeSessionsOf(id);
        verify(completions).clearParentAccessOf(id);
        verify(completions, never()).deletePersonalSessionsOfTutor(any());
        verify(children).maskAllOfParent(eq(id), eq("삭제됨"), eq("fox"), any(Instant.class));
        // 반 명단 연결은 기존처럼 해제한다.
        assertNull(linked.getLinkedParentUser());
        assertNull(linked.getChild());
        assertEquals(TutorStudentStatus.PENDING_PARENT, linked.getStatus());
        // 선생님 쪽 정리는 하지 않는다.
        verify(lessons, never()).deletePersonalLessonsOf(any());
        verify(classGroups, never()).deletePersonalClassesOf(any());
    }

    @Test
    void organizationTutorIsDetachedButOrganizationClassesStay() {
        AppUser tutor = user(Role.TUTOR);
        UUID id = tutor.getId();
        Organization org = Organization.builder().id(UUID.randomUUID()).build();
        when(orgTutors.findByTutor_IdOrderByJoinedAtAsc(id))
                .thenReturn(List.of(OrganizationTutor.builder().organization(org).tutor(tutor).build()));

        service.erase(tutor);

        verifyCommonCleanup(id);
        verify(orgTutorService).detachTutor(org.getId(), id);
        verify(orgTutors).deleteByTutor_Id(id);
        // 기관 소속이 아니었던 자기 반·수업만 지운다(기관 반·수업·기록은 쿼리 조건에서 빠진다).
        verify(completions).deletePersonalSessionsOfTutor(id);
        verify(lessons).deletePersonalLessonsOf(id);
        verify(students).deleteStudentsOfPersonalClasses(id);
        verify(homeroomHistory).deletePersonalHistoryOf(id);
        verify(classGroups).deletePersonalClassesOf(id);
        verify(children, never()).maskAllOfParent(any(), anyString(), anyString(), any());
    }

    @Test
    void independentTutorLosesOwnClassesLessonsAndStudents() {
        AppUser tutor = user(Role.TUTOR);
        UUID id = tutor.getId();
        when(orgTutors.findByTutor_IdOrderByJoinedAtAsc(id)).thenReturn(List.of());

        service.erase(tutor);

        verifyCommonCleanup(id);
        verify(orgTutorService, never()).detachTutor(any(), any());
        verify(completions).deletePersonalSessionsOfTutor(id);
        verify(lessons).deletePersonalLessonsOf(id);
        verify(students).deleteStudentsOfPersonalClasses(id);
        verify(homeroomHistory).deletePersonalHistoryOf(id);
        verify(classGroups).deletePersonalClassesOf(id);
    }

    @Test
    void directorKeepsOrganizationDataAndOnlyLosesPersonalData() {
        AppUser director = user(Role.DIRECTOR);
        Organization org = Organization.builder().id(UUID.randomUUID()).build();
        director.setOrganization(org);

        service.erase(director);

        verifyCommonCleanup(director.getId());
        assertEquals(org, director.getOrganization());
        verify(classGroups, never()).deletePersonalClassesOf(any());
        verify(completions, never()).deleteHomeSessionsOf(any());
        verify(children, never()).maskAllOfParent(any(), anyString(), anyString(), any());
    }

    @Test
    void profileImageObjectIsDeletedAndColumnsCleared() {
        AppUser tutor = user(Role.TUTOR);
        String objectName = "profiles/" + tutor.getId() + "/a.png";
        tutor.setProfileImageObjectName(objectName);
        tutor.setProfileImageUrl("https://x/" + objectName);
        when(config.supabase()).thenReturn(new AppProperties.Supabase("u", "k", null, null, null, null, "profile-images"));
        when(storage.delete("profile-images", objectName)).thenReturn(true);

        service.erase(tutor);

        verify(storage).delete("profile-images", objectName);
        assertNull(tutor.getProfileImageObjectName());
        assertNull(tutor.getProfileImageUrl());
        verify(deletionRetry, never()).enqueue(anyString(), anyString());
    }

    @Test
    void failedProfileImageDeleteIsQueuedForRetry() {
        AppUser tutor = user(Role.TUTOR);
        String objectName = "profiles/" + tutor.getId() + "/a.png";
        tutor.setProfileImageObjectName(objectName);
        when(config.supabase()).thenReturn(new AppProperties.Supabase("u", "k", null, null, null, null, "profile-images"));
        when(storage.delete("profile-images", objectName)).thenReturn(false);

        service.erase(tutor);

        verify(deletionRetry).enqueue("profile-images", objectName);
    }

    @Test
    void personalStudentsAreDeletedBeforeTheirClasses() {
        AppUser tutor = user(Role.TUTOR);
        when(orgTutors.findByTutor_IdOrderByJoinedAtAsc(tutor.getId())).thenReturn(List.of());

        service.erase(tutor);

        // 반을 먼저 지우면 tutor_student.class_group_id가 set null로 비어 반 id로 학생을 찾지 못한다.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(students, homeroomHistory, classGroups);
        order.verify(students).deleteStudentsOfPersonalClasses(tutor.getId());
        // 학생 반 이력(076)은 반 쪽 cascade가 없어 반보다 먼저 비운다.
        order.verify(students).deleteClassHistoryOfPersonalClasses(tutor.getId());
        order.verify(classGroups).deletePersonalClassesOf(tutor.getId());
    }

    /** 반이 없는 학생은 원장이 반을 지워 남은 기관 학생일 수 있어 지우지 않는다 - 기관 밖 자기 반의 학생만. */
    @Test
    void personalStudentQueryOnlyMatchesStudentsOfTheTutorsNonOrganizationClasses() throws Exception {
        String jpql = TutorStudentRepository.class.getMethod("deleteStudentsOfPersonalClasses", UUID.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class).value()
                .replaceAll("\s+", " ");
        assertTrue(jpql.contains("s.classGroup.id in"), jpql);
        assertTrue(jpql.contains("g.tutor.id = :tutorId and g.organization is null"), jpql);
        assertTrue(!jpql.contains("is null or"), "반 없는 학생을 포함하면 안 된다: " + jpql);
    }

    /**
     * organization 스냅샷이 비어 있어도(백필 전 기록) 기관 반이나 기관 반 수업에 묶인 기록은 지우지 않는다 -
     * 반·수업 조건은 null이면 통과, 아니면 기관 없는 반의 것만.
     */
    @Test
    void personalSessionQueryNeverMatchesSessionsOfOrganizationClassesOrLessons() throws Exception {
        String jpql = StoryCompletionRepository.class.getMethod("deletePersonalSessionsOfTutor", UUID.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class).value()
                .replaceAll("\\s+", " ");
        assertTrue(jpql.contains("c.user.id = :tutorId and c.organization is null"), jpql);
        assertTrue(jpql.contains("(c.classGroup is null or c.classGroup.id in "
                + "(select g.id from ClassGroup g where g.organization is null))"), jpql);
        assertTrue(jpql.contains("(c.lesson is null or c.lesson.id in "
                + "(select l.id from Lesson l where l.classGroup is null or l.classGroup.id in "
                + "(select lg.id from ClassGroup lg where lg.organization is null)))"), jpql);
        // c.classGroup.organization 같은 경로 조인은 반이 없는 기록을 inner join으로 떨어뜨린다.
        assertTrue(!jpql.contains("c.classGroup.organization") && !jpql.contains("c.lesson.classGroup"), jpql);
    }

    @Test
    void legacyDeletedAccountIsErasedAndKeepsItsOriginalDeletionTime() {
        AppUser user = user(Role.PARENT);
        Instant deletedAt = Instant.parse("2026-05-01T00:00:00Z");
        user.setDeletedAt(deletedAt);
        user.setLoginId("deleted:abc:mom@example.com");
        when(users.findById(user.getId())).thenReturn(java.util.Optional.of(user));

        service.eraseLegacyDeleted(user.getId());

        assertEquals(deletedAt, user.getDeletedAt());
        assertTrue(user.getEmail().endsWith("@deleted.invalid"), user.getEmail());
        assertTrue(!user.getLoginId().contains("mom@example.com"), user.getLoginId());
        assertEquals("탈퇴한 사용자", user.getDisplayName());
        verify(completions).deleteHomeSessionsOf(user.getId());
        verifyCommonCleanup(user.getId());
    }

    @Test
    void legacyErasureRefusesAnAccountThatIsNotDeleted() throws Exception {
        AppUser user = user(Role.PARENT);
        when(users.findById(user.getId())).thenReturn(java.util.Optional.of(user));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.eraseLegacyDeleted(user.getId()));

        assertEquals("mom@example.com", user.getEmail());
        verify(users, never()).save(any());
        assertNotNull(AccountErasureService.class.getMethod("eraseLegacyDeleted", UUID.class)
                .getAnnotation(Transactional.class));
    }

    /** 이미 익명화된 계정(@deleted.invalid)은 다시 잡지 않는다. */
    @Test
    void legacyDeletedQueriesSkipAlreadyAnonymizedAccounts() throws Exception {
        for (String method : List.of("findLegacyDeletedAccounts", "countLegacyDeletedAccounts")) {
            String jpql = AppUserRepository.class.getMethod(method)
                    .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
            assertTrue(jpql.contains("u.deletedAt is not null"), jpql);
            assertTrue(jpql.contains("u.email not like '%@deleted.invalid'"), jpql);
        }
    }

    /** 참여 행은 반 수업 기록의 참여자 명단이다 - 탈퇴한 보호자의 열람 권한만 비우고 행은 남긴다. */
    @Test
    void parentAccessToClassRecordsIsClearedNotDeleted() throws Exception {
        String sql = StoryCompletionRepository.class.getMethod("clearParentAccessOf", UUID.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
        assertTrue(sql.startsWith("update story_completion_participant set parent_user_id = null"), sql);
    }

    @Test
    void recordingsAreErasedBeforeHomeSessionsSoLinkedBetaSessionsCanStillBeFound() {
        AppUser parent = user(Role.PARENT);

        service.erase(parent);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(recordingConsent, completions);
        order.verify(recordingConsent).eraseForAccount(parent.getId());
        order.verify(completions).deleteHomeSessionsOf(parent.getId());
        verify(interactions).deleteAllByUserId(parent.getId());
    }
}
