package com.qstory.backend.tutor.lesson.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.dto.CreateLessonRequest;
import com.qstory.backend.tutor.lesson.dto.LessonResponse;
import com.qstory.backend.tutor.lesson.dto.UpdateLessonRequest;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IA "[3] 수업"의 CRUD. 학생/이야기 참조는 caller가 소유한 것만 허용 - 다른 선생님의 학생을
 * 자기 수업에 넣거나, 카탈로그에 없는 storyId를 넣는 것은 방지한다(단, story 카탈로그 검증은
 * story_completion과 같은 규약을 따라 하지 않는다 - 문자열 storyId로 두고 조회 시 카탈로그와
 * 조인해 표시하는 편이 시드/회수와의 마찰이 적어서).
 */
@Service
public class LessonService {

    private static final int MAX_NAME = 80;

    private final LessonRepository lessonRepository;
    private final TutorStudentRepository tutorStudentRepository;
    private final AppUserRepository userRepository;
    private final NotificationPublisher notificationPublisher;

    public LessonService(
            LessonRepository lessonRepository,
            TutorStudentRepository tutorStudentRepository,
            AppUserRepository userRepository,
            NotificationPublisher notificationPublisher) {
        this.lessonRepository = lessonRepository;
        this.tutorStudentRepository = tutorStudentRepository;
        this.userRepository = userRepository;
        this.notificationPublisher = notificationPublisher;
    }

    @Transactional(readOnly = true)
    public List<LessonResponse> listMine(CurrentUser caller, LessonStatus status) {
        var lessons = status == null
                ? lessonRepository.findByTutor_IdOrderByScheduledAtAscCreatedAtAsc(caller.userId())
                : lessonRepository.findByTutor_IdAndStatusOrderByScheduledAtAscCreatedAtAsc(caller.userId(), status);
        return lessons.stream().map(LessonResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public LessonResponse get(CurrentUser caller, UUID lessonId) {
        return LessonResponse.of(requireOwn(caller, lessonId));
    }

    @Transactional
    public LessonResponse create(CurrentUser caller, CreateLessonRequest request) {
        String name = requireName(request.name());
        Instant now = Instant.now();
        AppUser tutor = userRepository.getReferenceById(caller.userId());

        var students = resolveOwnedStudents(caller, request.studentIds());
        var storyIds = normalizeStoryIds(request.storyIds());

        Lesson saved = lessonRepository.save(Lesson.builder()
                .tutor(tutor)
                .name(name)
                .goal(trimOrNull(request.goal()))
                .scheduledAt(request.scheduledAt())
                .status(LessonStatus.SCHEDULED)
                .students(students)
                .storyIds(storyIds)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return LessonResponse.of(saved);
    }

    @Transactional
    public LessonResponse update(CurrentUser caller, UUID lessonId, UpdateLessonRequest request) {
        Lesson lesson = requireOwn(caller, lessonId);
        if (request.name() != null) lesson.setName(requireName(request.name()));
        // goal은 빈 문자열로 "지우기"를 허용 - 클라이언트가 명시적으로 ""을 보내면 null로 저장.
        if (request.goal() != null) lesson.setGoal(trimOrNull(request.goal()));
        if (request.scheduledAt() != null) lesson.setScheduledAt(request.scheduledAt());
        if (request.studentIds() != null) {
            lesson.setStudents(resolveOwnedStudents(caller, request.studentIds()));
        }
        if (request.storyIds() != null) {
            lesson.setStoryIds(normalizeStoryIds(request.storyIds()));
        }
        lesson.setUpdatedAt(Instant.now());
        return LessonResponse.of(lessonRepository.save(lesson));
    }

    @Transactional
    public LessonResponse start(CurrentUser caller, UUID lessonId) {
        Lesson lesson = requireOwn(caller, lessonId);
        if (lesson.getStatus() == LessonStatus.COMPLETED) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "이미 완료된 수업은 다시 시작할 수 없어요.");
        }
        if (lesson.getStatus() == LessonStatus.SCHEDULED) {
            lesson.setStartedAt(Instant.now());
        }
        lesson.setStatus(LessonStatus.IN_PROGRESS);
        lesson.setUpdatedAt(Instant.now());
        return LessonResponse.of(lessonRepository.save(lesson));
    }

    @Transactional
    public LessonResponse complete(CurrentUser caller, UUID lessonId) {
        Lesson lesson = requireOwn(caller, lessonId);
        if (lesson.getStatus() == LessonStatus.COMPLETED) {
            return LessonResponse.of(lesson);
        }
        Instant now = Instant.now();
        if (lesson.getStartedAt() == null) lesson.setStartedAt(now);
        lesson.setStatus(LessonStatus.COMPLETED);
        lesson.setCompletedAt(now);
        lesson.setUpdatedAt(now);
        Lesson saved = lessonRepository.save(lesson);
        notifyParentsOfCompletion(saved);
        return LessonResponse.of(saved);
    }

    // 이 수업이 실제로 앱 리더로 진행됐다면 StoryCompletionService가 이미 "tutor-report" 알림을
    // 보냈을 것이다(그쪽은 실제 완주 이벤트를 정확히 알지만, 이 Lesson과의 연결고리는 없다) - 이
    // 알림은 그 경로를 안 거치는 경우(오프라인/가정방문 수업을 튜터가 여기서만 완료 처리하는 경우)
    // 까지 부모에게 신호를 주기 위한 별도 kind("lesson-report")다. 같은 날 둘 다 발생하면 알림이
    // 두 번 갈 수 있는데, 서로 다른 이벤트를 가리키는 별개 알림이라 감수하기로 했다.
    private void notifyParentsOfCompletion(Lesson lesson) {
        for (TutorStudent student : lesson.getStudents()) {
            AppUser parent = student.getLinkedParentUser();
            if (parent == null) continue;
            notificationPublisher.publish(
                    parent.getId(),
                    "lesson-report",
                    student.getName() + "의 [" + lesson.getName() + "] 수업이 끝났어요",
                    "리포트에서 오늘 수업 기록을 확인해 보세요.",
                    "/reports",
                    "lesson-report:" + lesson.getId() + ":" + student.getId());
        }
    }

    @Transactional
    public void delete(CurrentUser caller, UUID lessonId) {
        lessonRepository.findByIdAndTutor_Id(lessonId, caller.userId())
                .ifPresent(lessonRepository::delete);
    }

    /* -------------------------------------------------------------- helpers */

    private Lesson requireOwn(CurrentUser caller, UUID lessonId) {
        return lessonRepository.findByIdAndTutor_Id(lessonId, caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "수업을 찾을 수 없어요.", 404));
    }

    private LinkedHashSet<TutorStudent> resolveOwnedStudents(CurrentUser caller, List<UUID> studentIds) {
        LinkedHashSet<TutorStudent> resolved = new LinkedHashSet<>();
        if (studentIds == null || studentIds.isEmpty()) return resolved;
        for (UUID studentId : studentIds) {
            TutorStudent student = tutorStudentRepository.findByIdAndTutor_Id(studentId, caller.userId())
                    .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "학생을 찾을 수 없어요.", 404));
            resolved.add(student);
        }
        return resolved;
    }

    private static HashSet<String> normalizeStoryIds(List<String> raw) {
        HashSet<String> normalized = new HashSet<>();
        if (raw == null) return normalized;
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            String trimmed = s.trim();
            if (trimmed.length() > 64) {
                throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "storyId 길이가 너무 길어요.");
            }
            normalized.add(trimmed);
        }
        return normalized;
    }

    private static String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "수업 이름을 입력해 주세요.");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_NAME) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "수업 이름이 너무 길어요.");
        }
        return trimmed;
    }

    private static String trimOrNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
