package com.qstory.backend.tutor.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.ChildAge;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.service.StudentClassHistoryService;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.parent.child.repository.ChildRepository;
import com.qstory.backend.tutor.TutorLessonType;
import com.qstory.backend.tutor.TutorStudentStatus;
import com.qstory.backend.tutor.dto.TutorStudentResponse;
import com.qstory.backend.tutor.dto.UpdateTutorStudentRequest;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선생님의 반 학생. 학생은 선생님이 따로 등록하지 않는다 - 학부모가 반 초대 링크로 아이 프로필을 연결하면
 * 반 명단에 올라간다(enrollParentInClass). 선생님은 명단을 보고 메모를 남기거나 학생을 지울 수 있다.
 */
@Service
public class TutorStudentService {

    /** fe entities/child/model/avatars.ts CHILD_AVATARS[0].key와 동일. */
    private static final String DEFAULT_CHILD_AVATAR_KEY = "fox";

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final TutorStudentRepository tutorStudentRepository;
    private final AppUserRepository userRepository;
    private final NotificationPublisher notificationPublisher;
    private final ChildRepository childRepository;
    private final LessonRepository lessonRepository;

    private final StudentClassHistoryService classHistoryService;

    public TutorStudentService(
            TutorStudentRepository tutorStudentRepository, AppUserRepository userRepository,
            NotificationPublisher notificationPublisher, ChildRepository childRepository,
            LessonRepository lessonRepository, StudentClassHistoryService classHistoryService) {
        this.classHistoryService = classHistoryService;
        this.tutorStudentRepository = tutorStudentRepository;
        this.userRepository = userRepository;
        this.notificationPublisher = notificationPublisher;
        this.childRepository = childRepository;
        this.lessonRepository = lessonRepository;
    }

    /**
     * 부모가 반 코드로 아이를 그 반의 학생 명단에 올리는 경로. 부모 계정에는 반 소속을 남기지 않고 학생 한 명
     * (CONFIRMED)으로 등록한다. 기관 반에 담임이 아직
     * 없으면 학생은 담임 없이 명단에만 올라가고, 원장이 담임을 배정하면 그 선생님의 학생이 된다(ClassService).
     * 같은 부모가 아이 여러 명을 올리려면 아이마다 이 경로를 한 번씩 거치면 된다.
     */
    @Transactional
    public TutorStudent enrollParentInClass(
            AppUser parent, ClassGroup classGroup, String childNameInput, Integer birthYearInput, UUID childId,
            UUID rosterStudentId) {
        // 이미 등록한 아이를 고르면 이름·출생연도는 그 아이 프로필에서 가져온다.
        Child chosenChild = childId == null ? null : childRepository.findByIdAndParent_Id(childId, parent.getId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "아이 프로필을 찾을 수 없어요.", 404));
        String childName = chosenChild != null ? chosenChild.getName() : childNameInput;
        if (chosenChild != null && chosenChild.getBirthYear() != null) birthYearInput = chosenChild.getBirthYear();
        if (chosenChild != null && birthYearInput == null) birthYearInput = birthYearFromAgeBand(chosenChild.getAgeBand());
        if (isBlank(childName)) {
            throw ApiException.contractError(ErrorCode.CHILD_INFO_REQUIRED, "아이 이름을 입력해 주세요.");
        }
        Integer birthYear = ChildAge.validateBirthYear(birthYearInput);
        if (birthYear == null) {
            throw ApiException.contractError(ErrorCode.CHILD_INFO_REQUIRED, "아이의 출생연도를 골라 주세요.");
        }
        AppUser tutor = classGroup.getTutor();
        Instant now = Instant.now();
        // 선생님이 미리 올려 둔(아직 학부모가 없는) 같은 이름의 학생이 있으면 새로 만들지 않고 그 학생에 잇는다 -
        // 일괄 등록 뒤 반 코드를 공유했거나, 학부모가 아이를 뺐다가 다시 올린 경우 명단에 같은 아이가 둘 생기지 않게.
        // 학부모가 명단에서 직접 고른 학생이 있으면 그 학생, 아니면 같은 이름의 학생에 잇는다.
        TutorStudent pending = rosterStudentId != null
                ? requirePendingRosterStudent(classGroup, rosterStudentId)
                : findPendingClassmate(classGroup, childName, birthYear);
        TutorStudent student;
        if (pending != null) {
            student = pending;
            if (student.getBirthYear() == null) {
                student.setBirthYear(birthYear);
                student.setAgeBand(ChildAge.tutorLabel(birthYear));
            }
            if (student.getTutor() == null) student.setTutor(tutor);
        } else {
            student = TutorStudent.builder()
                    .tutor(tutor)
                    .name(childName.trim())
                    .ageBand(ChildAge.tutorLabel(birthYear))
                    .birthYear(birthYear)
                    .lessonType(TutorLessonType.CLASS)
                    .classGroup(classGroup)
                    .createdAt(now)
                    .build();
        }
        // 아이 프로필을 먼저 정하고 중복을 확인한 뒤에 학생에 붙인다 - 기존 학생(pending)에 먼저 붙이면 조회 전
        // 자동 flush로 그 행이 먼저 저장돼 자기 자신이 "이미 등록된 아이"로 잡힌다.
        // 명단의 다른 이름 학생을 골랐어도 아이 프로필은 학부모가 적은 이름으로 찾거나 만든다.
        Child child = resolveChild(parent, student, childId, childName.trim());
        // 담임이 없는 반은 (tutor_id, child_id) 유니크 인덱스가 걸리지 않아 같은 아이가 중복으로 올라올 수 있다.
        if (tutorStudentRepository.existsByClassGroup_IdAndChild_IdAndDeletedAtIsNull(classGroup.getId(), child.getId())) {
            throw ApiException.contractError(ErrorCode.DUPLICATE_CHILD_LINK, "이 아이는 이미 이 반에 등록되어 있어요.", 409);
        }
        student.setStatus(TutorStudentStatus.CONFIRMED);
        student.setLinkedParentUser(parent);
        student.setLinkedAt(now);
        student.setChild(child);
        try {
            student = tutorStudentRepository.saveAndFlush(student);
        } catch (DataIntegrityViolationException duplicate) {
            throw ApiException.contractError(
                    ErrorCode.DUPLICATE_CHILD_LINK, "이 아이는 이미 이 선생님의 학생으로 등록되어 있어요.", 409);
        }
        // 반 소속 이력(076) - 새로 들어온 학생은 JOINED 구간을 연다. 명단에 미리 있던 학생은 이미 열린 구간을 그대로 둔다.
        classHistoryService.recordJoined(student, classGroup, now);
        if (tutor != null) {
            addToScheduledClassLessons(tutor.getId(), student, classGroup);
        }
        notifyClassEnrollment(parent, student, classGroup);
        return student;
    }

    /**
     * 같은 반에서 학부모가 아직 없는 같은 이름(공백·대소문자 무시)의 학생. 여럿이면 출생연도가 같은 학생, 그래도
     * 여럿이면 먼저 등록된 학생. 고른 학생은 잠가서 두 학부모가 동시에 같은 학생에 이어지지 않게 한다.
     */
    private TutorStudent findPendingClassmate(ClassGroup classGroup, String childName, Integer birthYear) {
        String wanted = normalizeName(childName);
        List<TutorStudent> sameName = tutorStudentRepository
                .findByClassGroup_IdAndLinkedParentUserIsNullAndDeletedAtIsNull(classGroup.getId()).stream()
                .filter(candidate -> normalizeName(candidate.getName()).equals(wanted))
                .sorted(java.util.Comparator.comparing(TutorStudent::getCreatedAt))
                .toList();
        if (sameName.isEmpty()) return null;
        TutorStudent chosen = sameName.stream()
                .filter(candidate -> birthYear.equals(candidate.getBirthYear()))
                .findFirst()
                .orElse(sameName.get(0));
        return tutorStudentRepository.lockById(chosen.getId())
                .filter(locked -> locked.getLinkedParentUser() == null && locked.getDeletedAt() == null)
                .orElse(null);
    }

    /** 학부모가 고른 명단 학생 - 이 반의, 아직 학부모가 없는 학생이어야 한다. 잠가서 두 학부모가 동시에 잇지 못하게 한다. */
    private TutorStudent requirePendingRosterStudent(ClassGroup classGroup, UUID rosterStudentId) {
        return tutorStudentRepository.lockById(rosterStudentId)
                .filter(student -> student.getDeletedAt() == null
                        && student.getClassGroup() != null
                        && student.getClassGroup().getId().equals(classGroup.getId()))
                .map(student -> {
                    if (student.getLinkedParentUser() != null) {
                        throw ApiException.contractError(
                                ErrorCode.DUPLICATE_CHILD_LINK, "이 학생은 이미 다른 보호자와 연결되어 있어요. 선생님께 확인해 주세요.", 409);
                    }
                    return student;
                })
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "반 명단에서 학생을 찾을 수 없어요.", 404));
    }

    /** 담임에게, 담임이 아직 없으면 기관 원장에게 알린다. */
    private void notifyClassEnrollment(AppUser parent, TutorStudent student, ClassGroup classGroup) {
        String body = parent.getDisplayName() + "님이 " + classGroup.getName() + " 반에 " + student.getName() + "을(를) 등록했어요.";
        if (classGroup.getTutor() != null) {
            notificationPublisher.publish(
                    classGroup.getTutor().getId(), "tutor-class-parent-joined",
                    student.getName() + " 부모님이 반 코드로 들어왔어요", body,
                    "/tutor/students/" + student.getId(), "tutor-class-parent-joined:" + student.getId());
            return;
        }
        if (classGroup.getOrganization() == null) {
            return;
        }
        userRepository.findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(classGroup.getOrganization().getId(), Role.DIRECTOR)
                .ifPresent(director -> notificationPublisher.publish(
                        director.getId(), "class-parent-joined",
                        "새 학부모가 반에 합류했어요", body,
                        "/organization/classes/" + classGroup.getId(), "class-parent-joined:" + student.getId()));
    }

    @Transactional(readOnly = true)
    public TutorStudentResponse getStudent(CurrentUser caller, UUID studentId) {
        return TutorStudentResponse.of(requireOwnedStudent(caller, studentId));
    }

    @Transactional
    public TutorStudentResponse updateStudent(CurrentUser caller, UUID studentId, UpdateTutorStudentRequest request) {
        TutorStudent student = requireOwnedStudent(caller, studentId);
        // 각 필드가 null이면 그대로 두고, 값이 있으면 반영. 빈 문자열은 명시적 "지우기".
        if (request.classType() != null) {
            String trimmed = request.classType().trim();
            student.setClassType(trimmed.isEmpty() ? null : trimmed);
        }
        if (request.prepNote() != null) {
            String trimmed = request.prepNote().trim();
            student.setPrepNote(trimmed.isEmpty() ? null : trimmed);
        }
        if (request.birthYear() != null) {
            Integer birthYear = ChildAge.validateBirthYear(request.birthYear());
            student.setBirthYear(birthYear);
            student.setAgeBand(ChildAge.tutorLabel(birthYear));
        }
        return TutorStudentResponse.of(tutorStudentRepository.save(student));
    }

    @Transactional(readOnly = true)
    public List<TutorStudentResponse> listStudents(CurrentUser caller) {
        return tutorStudentRepository.findByTutor_IdAndDeletedAtIsNullOrderByCreatedAtAsc(caller.userId()).stream()
                .map(TutorStudentResponse::of)
                .toList();
    }

    /**
     * 소프트 삭제(053) - 행을 남겨야 story_completion이 이 학생을 계속 가리켜 부모가 선생님 리포트를 잃지 않는다.
     * deletedAt을 채우고 예정 수업 참여자에서 뺀다. 이미 끝난 수업·완주 기록은 그대로다.
     */
    @Transactional
    public void deleteStudent(CurrentUser caller, UUID studentId) {
        TutorStudent student = requireOwnedStudent(caller, studentId);
        Instant now = Instant.now();
        student.setDeletedAt(now);
        for (Lesson lesson : lessonRepository.findByTutor_IdAndStatusAndStudents_Id(
                caller.userId(), LessonStatus.SCHEDULED, student.getId())) {
            lesson.getStudents().removeIf(participant -> participant.getId().equals(student.getId()));
            lesson.setUpdatedAt(now);
            lessonRepository.save(lesson);
        }
        tutorStudentRepository.save(student);
    }

    /** 반에 새로 들어온 학생을 그 반의 예정·진행 중인 수업에 참여자로 넣는다. */
    private void addToScheduledClassLessons(UUID tutorId, TutorStudent student, ClassGroup classGroup) {
        // 예정 수업뿐 아니라 진행 중인 수업에도 - 수업을 연 뒤 반 초대 링크로 들어온 아이도 그 수업 기록에 함께 남는다.
        List<Lesson> openLessons = new ArrayList<>(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(
                tutorId, classGroup.getId(), LessonStatus.SCHEDULED));
        openLessons.addAll(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(
                tutorId, classGroup.getId(), LessonStatus.IN_PROGRESS));
        for (Lesson lesson : openLessons) {
            if (lesson.getStudents().stream().noneMatch(p -> p.getId().equals(student.getId()))) {
                lesson.getStudents().add(student);
                lesson.setUpdatedAt(Instant.now());
                lessonRepository.save(lesson);
            }
        }
    }

    /**
     * 반에 올릴 때 학생에 붙일 아이 프로필. (1) 부모가 childId를 지정했으면 본인 소유인지 확인해 그 아이,
     * (2) 아니면 같은 이름(공백·대소문자 무시)의 기존 아이 - 여럿이면 출생연도로 좁히고 그래도 여럿이면
     * 409 CHILD_SELECTION_REQUIRED로 부모가 childId를 고르게 한다, (3) 없으면 적은 이름·학생 연령대로
     * 새 아이를 만든다. 새로 만드는 경우 아바타는 프론트 프리셋 첫 번째('fox')와 같은 기본값이라
     * 부모가 프로필에서 바꿀 수 있다. 이미 이 학생에 아이가 붙어 있으면(같은 부모의 재수락) 그대로 둔다.
     */
    private Child resolveChild(AppUser parent, TutorStudent student, UUID requestedChildId, String preferredName) {
        String childName = preferredName != null && !preferredName.isBlank() ? preferredName : student.getName();
        if (requestedChildId != null) {
            return childRepository.findByIdAndParent_Id(requestedChildId, parent.getId())
                    .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "아이 프로필을 찾을 수 없어요.", 404));
        }
        if (student.getChild() != null && student.getChild().getParent().getId().equals(parent.getId())) {
            return student.getChild();
        }
        // 이름 매칭 - 선생님이 적은 별명이라 형제나 같은 별명의 아이와 겹칠 수 있다. 후보가 여럿이면
        // 출생연도로 좁히고, 그래도 여럿이면 조용히 첫 아이를 고르지 않고 부모가 childId로 고르게 한다.
        String wanted = normalizeName(childName);
        List<Child> matches = new ArrayList<>();
        for (Child existing : childRepository.findByParent_IdOrderByCreatedAtAsc(parent.getId())) {
            if (normalizeName(existing.getName()).equals(wanted)) matches.add(existing);
        }
        if (matches.size() > 1 && student.getBirthYear() != null) {
            List<Child> sameYear = matches.stream()
                    .filter(c -> student.getBirthYear().equals(c.getBirthYear()))
                    .toList();
            if (!sameYear.isEmpty()) matches = sameYear;
        }
        if (matches.size() > 1) {
            throw ApiException.contractError(
                    ErrorCode.CHILD_SELECTION_REQUIRED,
                    "같은 이름의 아이가 여러 명이에요. 연결할 아이 프로필을 골라 다시 수락해 주세요.", 409);
        }
        if (matches.size() == 1) return matches.get(0);
        Instant now = Instant.now();
        return childRepository.save(Child.builder()
                .parent(parent)
                .name(childName)
                .ageBand(student.getBirthYear() != null
                        ? ChildAge.parentBand(student.getBirthYear())
                        : childAgeBandFor(student.getAgeBand()))
                .birthYear(student.getBirthYear())
                .avatarKey(DEFAULT_CHILD_AVATAR_KEY)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /** 출생연도 없이 연령대("6-7")만 있는 예전 아이 프로필 - 낮은 나이로 출생연도를 짐작한다. 모르면 null. */
    private static Integer birthYearFromAgeBand(String ageBand) {
        if (ageBand == null) return null;
        java.util.regex.Matcher age = DIGITS.matcher(ageBand);
        return age.find() ? ChildAge.currentYear() - Integer.parseInt(age.group()) : null;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * 선생님 쪽 학생 연령대는 "7세"처럼 한 살 단위, 부모 쪽 아이 프로필은 "6-7" 같은 구간이다
     * (fe entities/child/model/age-band.ts의 ageBandFromLabel과 같은 규칙). 숫자가 없으면 "6-7".
     */
    static String childAgeBandFor(String tutorAgeBand) {
        Matcher matcher = DIGITS.matcher(tutorAgeBand == null ? "" : tutorAgeBand);
        if (!matcher.find()) return "6-7";
        int age = Integer.parseInt(matcher.group());
        if (age <= 5) return "4-5";
        if (age <= 7) return "6-7";
        if (age <= 9) return "8-9";
        if (age <= 11) return "10-11";
        return "12+";
    }

    private TutorStudent requireOwnedStudent(CurrentUser caller, UUID studentId) {
        return tutorStudentRepository.findByIdAndTutor_IdAndDeletedAtIsNull(studentId, caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "학생을 찾을 수 없어요.", 404));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
