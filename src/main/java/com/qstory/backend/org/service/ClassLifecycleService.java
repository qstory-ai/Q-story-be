package com.qstory.backend.org.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.dto.ClassHistoryEntryResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.dto.MoveStudentsRequest;
import com.qstory.backend.org.dto.MoveStudentsResponse;
import com.qstory.backend.org.dto.RenameClassRequest;
import com.qstory.backend.org.dto.SkippedStudent;
import com.qstory.backend.org.dto.TermTransitionRequest;
import com.qstory.backend.org.dto.TermTransitionResponse;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassMembershipReason;
import com.qstory.backend.org.entity.TutorStudentClassHistory;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.repository.ClassHomeroomInviteRepository;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반 수명주기(076) - 원장이 반 이름을 바꾸고, 다 쓴 반을 지난 반으로 보관하고, 학생을 같은 기관의 다른 반으로 옮기고,
 * 학기를 넘긴다(학생마다 옮김·유지·졸업). 반과 학생 행은 지우지 않는다.
 *
 * <p>옮기기: 학생 행(tutor_student)은 그대로 두고 반(class_group_id)과 담당 선생님(tutor_id → 옮긴 반의 담임, 없으면 null)만
 * 바꾼다 - 같은 행이라 지난 수업 기록(participants)과 학부모 연결이 끊기지 않는다. 지난 반의 예정·진행 중 수업에서 빠지고 옮긴 반의
 * 예정·진행 중 수업에 들어간다. 반 소속 이력(tutor_student_class_history)이 남는다.
 *
 * <p>졸업: graduated_at을 채우고 담당 선생님을 비운다. 반과 학부모 연결은 남아 학부모는 지난 리포트를 계속 보고, 원장과 지난
 * 담임은 지난 반에서 이 학생을 본다. 지금 명단·이용권·인원수에서는 빠진다.
 */
@Service
public class ClassLifecycleService {

    static final String MOVE = "MOVE";
    static final String KEEP = "KEEP";
    static final String GRADUATE = "GRADUATE";

    static final String HAS_ACTIVE_STUDENTS_DETAIL =
            "아직 이 반에 학생이 있어요. 학기 넘기기로 학생을 다른 반으로 옮기거나 졸업 처리한 뒤 지난 반으로 보관해 주세요.";

    private final ClassService classService;
    private final ClassGroupRepository classGroupRepository;
    private final TutorStudentRepository tutorStudentRepository;
    private final StudentClassHistoryService classHistoryService;
    private final ClassHomeroomHistoryService homeroomHistoryService;
    private final ClassHomeroomInviteRepository homeroomInviteRepository;
    private final OrganizationTutorRepository organizationTutorRepository;
    private final LessonRepository lessonRepository;
    private final AppUserRepository userRepository;
    private final NotificationPublisher notificationPublisher;

    public ClassLifecycleService(
            ClassService classService, ClassGroupRepository classGroupRepository,
            TutorStudentRepository tutorStudentRepository, StudentClassHistoryService classHistoryService,
            ClassHomeroomHistoryService homeroomHistoryService, ClassHomeroomInviteRepository homeroomInviteRepository,
            OrganizationTutorRepository organizationTutorRepository, LessonRepository lessonRepository,
            AppUserRepository userRepository, NotificationPublisher notificationPublisher) {
        this.classService = classService;
        this.classGroupRepository = classGroupRepository;
        this.tutorStudentRepository = tutorStudentRepository;
        this.classHistoryService = classHistoryService;
        this.homeroomHistoryService = homeroomHistoryService;
        this.homeroomInviteRepository = homeroomInviteRepository;
        this.organizationTutorRepository = organizationTutorRepository;
        this.lessonRepository = lessonRepository;
        this.userRepository = userRepository;
        this.notificationPublisher = notificationPublisher;
    }

    /** 반 이름 바꾸기 - 지난 반도 바꿀 수 있다. 지난 리포트는 그때 이름(story_completion.class_name)으로 남는다. */
    @Transactional
    public ClassResponse rename(CurrentUser caller, UUID classId, RenameClassRequest request) {
        ClassGroup classGroup = classService.requireOwnedByDirector(caller, classId);
        classGroup.setName(ClassService.requireClassName(request == null ? null : request.name()));
        return ClassResponse.of(classGroupRepository.save(classGroup));
    }

    /**
     * 지난 반으로 보관한다. 지금 학생(졸업 전)이 남아 있으면 409 - 학기 넘기기로 옮기거나 졸업시킨 뒤에 보관한다(남은 학생을
     * "보관된 반의 학생"으로 두면 담임·이용권·명단이 어느 쪽도 아닌 상태가 된다). 살아 있는 담임 초대는 거둔다. 담임은
     * 그대로 둔다 - 지난 반에서 꺼내면 그대로 다시 쓴다. 이미 지난 반이면 그대로 돌려준다.
     */
    @Transactional
    public ClassResponse archive(CurrentUser caller, UUID classId) {
        ClassGroup classGroup = classService.requireOwnedByDirector(caller, classId);
        if (classGroup.isArchived()) {
            return ClassResponse.of(classGroup);
        }
        if (!tutorStudentRepository.findByClassGroup_IdAndDeletedAtIsNullOrderByCreatedAtAsc(classId).isEmpty()) {
            throw ApiException.contractError(ErrorCode.CLASS_HAS_ACTIVE_STUDENTS, HAS_ACTIVE_STUDENTS_DETAIL, 409);
        }
        archiveNow(caller, classGroup, Instant.now());
        return ClassResponse.of(classGroup);
    }

    /** 지난 반에서 꺼낸다 - 반 코드가 다시 살아난다. 담임 초대는 새로 발급해야 한다. */
    @Transactional
    public ClassResponse unarchive(CurrentUser caller, UUID classId) {
        ClassGroup classGroup = classService.requireOwnedByDirector(caller, classId);
        if (classGroup.isArchived()) {
            classGroup.setArchivedAt(null);
            classGroup.setArchivedBy(null);
            classGroupRepository.save(classGroup);
        }
        return ClassResponse.of(classGroup);
    }

    /**
     * 학생을 같은 기관의 다른 반으로 옮긴다. 옮길 반이 없거나 다른 기관 반이면 404, 지난 반이면 409. 학생마다 옮기지 못한
     * 이유는 skipped로 알린다(요청 전체를 실패시키지 않는다).
     */
    @Transactional
    public MoveStudentsResponse moveStudents(CurrentUser caller, UUID classId, MoveStudentsRequest request) {
        ClassGroup source = classService.requireOwnedByDirector(caller, classId);
        if (request == null || request.studentIds() == null || request.studentIds().isEmpty()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "옮길 학생을 골라 주세요.");
        }
        ClassGroup target = requireTarget(source, request.targetClassId());
        Instant now = Instant.now();
        List<UUID> moved = new ArrayList<>();
        List<SkippedStudent> skipped = new ArrayList<>();
        for (UUID studentId : new LinkedHashSet<>(request.studentIds())) {
            TutorStudent student = studentId == null ? null : tutorStudentRepository.findById(studentId).orElse(null);
            String reason = moveOne(caller, source, student, target, now);
            if (reason == null) moved.add(studentId);
            else skipped.add(new SkippedStudent(studentId, reason));
        }
        return new MoveStudentsResponse(moved, skipped);
    }

    /**
     * 학기 넘기기 - 이 반의 지금 학생 모두에게 MOVE | KEEP | GRADUATE를 한 번에, 한 트랜잭션으로 적용한다. 빠진 학생이
     * 있거나 이 반 학생이 아닌 id, 같은 학생 두 번, 알 수 없는 action, MOVE에 옮길 반이 없으면 400(아무것도 바꾸지 않는다).
     * archiveClass면 끝난 뒤 이 반을 보관한다 - KEEP이 있으면 400, 옮기지 못한 학생이 남으면 409로 전체를 되돌린다.
     */
    @Transactional
    public TermTransitionResponse termTransition(CurrentUser caller, UUID classId, TermTransitionRequest request) {
        ClassGroup source = classService.requireOwnedByDirector(caller, classId);
        List<TermTransitionRequest.Decision> decisions =
                request == null || request.decisions() == null ? List.of() : request.decisions();
        boolean archiveClass = request != null && request.archiveClass();

        Map<UUID, TutorStudent> active = new HashMap<>();
        for (TutorStudent student : tutorStudentRepository.findByClassGroup_IdAndDeletedAtIsNullOrderByCreatedAtAsc(classId)) {
            active.put(student.getId(), student);
        }
        Set<UUID> decided = new HashSet<>();
        Map<UUID, ClassGroup> targets = new HashMap<>();
        for (TermTransitionRequest.Decision decision : decisions) {
            if (decision == null || decision.studentId() == null || !active.containsKey(decision.studentId())) {
                throw invalid("이 반의 지금 학생이 아닌 학생이 들어 있어요. 명단을 새로 불러와 다시 정해 주세요.");
            }
            if (!decided.add(decision.studentId())) {
                throw invalid("한 학생에게 두 번 정했어요. 학생마다 하나만 골라 주세요.");
            }
            String action = decision.action() == null ? "" : decision.action().trim().toUpperCase();
            switch (action) {
                case MOVE -> {
                    if (decision.targetClassId() == null) {
                        throw invalid("옮길 반을 골라 주세요.");
                    }
                    if (decision.targetClassId().equals(classId)) {
                        throw invalid("지금 반에 그대로 두려면 '유지'를 골라 주세요.");
                    }
                    targets.computeIfAbsent(decision.targetClassId(), id -> requireTarget(source, id));
                }
                case KEEP -> {
                    if (archiveClass) {
                        throw invalid("반을 지난 반으로 보관하려면 이 반에 남는 학생이 없어야 해요.");
                    }
                }
                case GRADUATE -> { }
                default -> throw invalid("학생마다 옮김(MOVE), 유지(KEEP), 졸업(GRADUATE) 중 하나를 골라 주세요.");
            }
        }
        if (!decided.containsAll(active.keySet())) {
            throw invalid("이 반 학생 모두의 다음 학기를 정해 주세요.");
        }

        Instant now = Instant.now();
        List<UUID> moved = new ArrayList<>();
        List<UUID> kept = new ArrayList<>();
        List<UUID> graduated = new ArrayList<>();
        List<SkippedStudent> skipped = new ArrayList<>();
        for (TermTransitionRequest.Decision decision : decisions) {
            TutorStudent student = active.get(decision.studentId());
            switch (decision.action().trim().toUpperCase()) {
                case MOVE -> {
                    String reason = moveOne(caller, source, student, targets.get(decision.targetClassId()), now);
                    if (reason == null) moved.add(student.getId());
                    else skipped.add(new SkippedStudent(student.getId(), reason));
                }
                case KEEP -> {
                    classHistoryService.transfer(
                            student, source, ClassMembershipReason.KEPT, ClassMembershipReason.KEPT, now, caller.userId());
                    kept.add(student.getId());
                }
                default -> {
                    graduate(caller, source, student, now);
                    graduated.add(student.getId());
                }
            }
        }
        boolean archived = false;
        if (archiveClass && !source.isArchived()) {
            if (!skipped.isEmpty()) {
                // 옮기지 못한 학생이 이 반에 남는다 - 보관하지 않고 전체를 되돌린다(알림은 커밋 뒤에만 나간다).
                throw ApiException.contractError(ErrorCode.CLASS_HAS_ACTIVE_STUDENTS,
                        "옮기지 못한 학생이 있어 반을 보관하지 않았어요. 그 학생을 다른 반으로 정하거나 졸업 처리해 주세요.", 409);
            }
            archiveNow(caller, source, now);
            archived = true;
        }
        return new TermTransitionResponse(moved, kept, graduated, archived, skipped);
    }

    /**
     * 학생의 반 이력 - 기관 원장, 그리고 이 학생의 지금 담임이나 이 학생이 있었던 반의 (지난) 담임(아직 그 기관 소속).
     * 아니면 403, 학생이 없거나 기관 반 학생이 아니면 404.
     */
    @Transactional(readOnly = true)
    public List<ClassHistoryEntryResponse> classHistory(CurrentUser caller, UUID studentId) {
        TutorStudent student = tutorStudentRepository.findById(studentId)
                .filter(found -> found.getDeletedAt() == null && found.getClassGroup() != null)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "학생을 찾을 수 없어요.", 404));
        List<TutorStudentClassHistory> history = classHistoryService.list(student.getId());
        UUID organizationId = student.getClassGroup().getOrganization() == null
                ? null : student.getClassGroup().getOrganization().getId();
        boolean director = caller.role() == Role.DIRECTOR && organizationId != null
                && organizationId.equals(caller.orgId());
        boolean homeroom = caller.role() == Role.TUTOR && (
                (student.getTutor() != null && student.getTutor().getId().equals(caller.userId()))
                || (organizationId != null
                        && organizationTutorRepository.findByOrganization_IdAndTutor_Id(organizationId, caller.userId()).isPresent()
                        && history.stream().anyMatch(entry ->
                                homeroomHistoryService.hasLed(entry.getClassGroup().getId(), caller.userId()))));
        if (!director && !homeroom) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 학생의 반 이력을 볼 권한이 없어요.", 403);
        }
        return history.stream().map(ClassHistoryEntryResponse::of).toList();
    }

    // ---- 내부 ----

    private ClassGroup requireTarget(ClassGroup source, UUID targetClassId) {
        if (targetClassId == null) {
            throw invalid("옮길 반을 골라 주세요.");
        }
        ClassGroup target = classGroupRepository.findById(targetClassId)
                .filter(found -> found.getOrganization() != null && source.getOrganization() != null
                        && found.getOrganization().getId().equals(source.getOrganization().getId()))
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "옮길 반을 찾을 수 없어요.", 404));
        ClassService.requireNotArchived(target);
        return target;
    }

    /** 한 학생을 옮긴다. 옮겼으면 null, 못 옮겼으면 그 이유(SkippedStudent의 값). */
    private String moveOne(CurrentUser caller, ClassGroup source, TutorStudent student, ClassGroup target, Instant now) {
        if (student == null || student.getDeletedAt() != null || student.getClassGroup() == null
                || !student.getClassGroup().getId().equals(source.getId())) {
            return SkippedStudent.NOT_IN_CLASS;
        }
        if (student.isGraduated()) {
            return SkippedStudent.GRADUATED;
        }
        if (target.getId().equals(source.getId())) {
            return SkippedStudent.SAME_CLASS;
        }
        if (student.getChild() != null
                && tutorStudentRepository.existsByClassGroup_IdAndChild_IdAndDeletedAtIsNull(target.getId(), student.getChild().getId())) {
            return SkippedStudent.ALREADY_IN_TARGET;
        }
        AppUser homeroom = target.getTutor() != null && target.getTutor().getDeletedAt() == null ? target.getTutor() : null;
        // (tutor_id, child_id) 유니크 - 옮길 반 담임에게 같은 아이가 다른 학생 등록으로 이미 있으면 옮기지 않는다.
        if (homeroom != null && student.getChild() != null
                && tutorStudentRepository.existsByTutor_IdAndChild_IdAndDeletedAtIsNullAndIdNot(
                        homeroom.getId(), student.getChild().getId(), student.getId())) {
            return SkippedStudent.ALREADY_TUTOR_STUDENT;
        }

        removeFromOldClassLessons(student, source, now);
        student.setClassGroup(target);
        student.setTutor(homeroom);
        tutorStudentRepository.save(student);
        if (homeroom != null) {
            addToOpenLessons(homeroom, student, target, now);
        }
        TutorStudentClassHistory entry = classHistoryService.transfer(
                student, target, ClassMembershipReason.MOVED, ClassMembershipReason.MOVED, now, caller.userId());
        notifyMoved(student, source, target, homeroom, entry);
        return null;
    }

    private void graduate(CurrentUser caller, ClassGroup source, TutorStudent student, Instant now) {
        removeFromOldClassLessons(student, source, now);
        student.setGraduatedAt(now);
        // 담당 선생님을 비운다 - 선생님의 학생 목록·수업 자동 채우기에서 빠지고, (tutor_id, child_id) 유니크도 풀린다.
        student.setTutor(null);
        tutorStudentRepository.save(student);
        TutorStudentClassHistory entry =
                classHistoryService.close(student, source, ClassMembershipReason.GRADUATED, now, caller.userId());
        notifyGraduated(student, source, entry);
    }

    private void archiveNow(CurrentUser caller, ClassGroup classGroup, Instant now) {
        classGroup.setArchivedAt(now);
        classGroup.setArchivedBy(userRepository.getReferenceById(caller.userId()));
        classGroupRepository.save(classGroup);
        homeroomInviteRepository.revokeLiveByClassGroupId(classGroup.getId(), now);
    }

    /**
     * 지난 반의 예정·진행 중 수업의 참여 학생에서 뺀다 - 그대로 두면 반을 떠난 뒤에 저장한 지난 반 수업 기록에도 이 학생이
     * 참여 학생으로 남아 학부모에게 보인다. 끝난 수업과 이미 남은 기록은 그대로다.
     */
    private void removeFromOldClassLessons(TutorStudent student, ClassGroup source, Instant now) {
        if (student.getTutor() == null) return;
        List<Lesson> open = new ArrayList<>(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(
                student.getTutor().getId(), source.getId(), LessonStatus.SCHEDULED));
        open.addAll(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(
                student.getTutor().getId(), source.getId(), LessonStatus.IN_PROGRESS));
        for (Lesson lesson : open) {
            if (lesson.getStudents().removeIf(participant -> participant.getId().equals(student.getId()))) {
                lesson.setUpdatedAt(now);
                lessonRepository.save(lesson);
            }
        }
    }

    /** 옮긴 반의 예정·진행 중 수업에 참여 학생으로 넣는다(반 코드로 들어온 학생과 같다 - TutorStudentService). */
    private void addToOpenLessons(AppUser homeroom, TutorStudent student, ClassGroup target, Instant now) {
        List<Lesson> open = new ArrayList<>(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(
                homeroom.getId(), target.getId(), LessonStatus.SCHEDULED));
        open.addAll(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(
                homeroom.getId(), target.getId(), LessonStatus.IN_PROGRESS));
        for (Lesson lesson : open) {
            if (lesson.getStudents().stream().noneMatch(participant -> participant.getId().equals(student.getId()))) {
                lesson.getStudents().add(student);
                lesson.setUpdatedAt(now);
                lessonRepository.save(lesson);
            }
        }
    }

    private void notifyMoved(
            TutorStudent student, ClassGroup source, ClassGroup target, AppUser homeroom, TutorStudentClassHistory entry) {
        String child = student.getName();
        if (student.getLinkedParentUser() != null && student.getLinkedParentUser().getDeletedAt() == null) {
            notificationPublisher.publish(
                    student.getLinkedParentUser().getId(),
                    "class-student-moved",
                    NotificationText.title(KoreanParticle.subject(child) + " " + KoreanParticle.direction(target.getName()) + " 옮겼어요"),
                    NotificationText.body(source.getName() + "에서 함께한 수업 리포트도 그대로 볼 수 있어요."),
                    "/reports",
                    "class-student-moved:" + entry.getId());
        }
        if (homeroom != null) {
            notificationPublisher.publish(
                    homeroom.getId(),
                    "class-student-moved-in",
                    NotificationText.title(KoreanParticle.subject(child) + " " + target.getName() + "에 들어왔어요"),
                    NotificationText.body(source.getName() + "에서 옮겨 왔어요. 지난 반 수업 기록도 학생 화면에서 이어서 볼 수 있어요."),
                    "/tutor/students/" + student.getId(),
                    "class-student-moved-in:" + entry.getId());
        }
    }

    private void notifyGraduated(TutorStudent student, ClassGroup source, TutorStudentClassHistory entry) {
        if (student.getLinkedParentUser() == null || student.getLinkedParentUser().getDeletedAt() != null) return;
        String child = student.getName();
        notificationPublisher.publish(
                student.getLinkedParentUser().getId(),
                "class-student-graduated",
                NotificationText.title(KoreanParticle.subject(child) + " " + source.getName() + " 수업을 마쳤어요"),
                NotificationText.body(child + "의 " + source.getName() + " 수업 기록은 계속 볼 수 있어요."),
                "/reports",
                "class-student-graduated:" + entry.getId());
    }

    private static ApiException invalid(String detail) {
        return ApiException.contractError(ErrorCode.VALIDATION_FAILED, detail, 400);
    }
}
