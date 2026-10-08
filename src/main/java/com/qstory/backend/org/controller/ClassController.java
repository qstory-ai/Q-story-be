package com.qstory.backend.org.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.dto.AssignHomeroomRequest;
import com.qstory.backend.org.dto.ClassMembershipResponse;
import com.qstory.backend.org.dto.ClassPreviewResponse;
import com.qstory.backend.org.dto.ClassRosterEntryResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.dto.ClassReportResponse;
import com.qstory.backend.org.dto.ClassStudentReportResponse;
import com.qstory.backend.org.dto.ClassStudentResponse;
import com.qstory.backend.org.dto.HomeroomHistoryEntryResponse;
import com.qstory.backend.org.dto.CreateClassRequest;
import com.qstory.backend.org.dto.JoinClassRequest;
import com.qstory.backend.org.dto.JoinExistingClassRequest;
import com.qstory.backend.org.dto.ClassHistoryEntryResponse;
import com.qstory.backend.org.dto.MoveStudentsRequest;
import com.qstory.backend.org.dto.MoveStudentsResponse;
import com.qstory.backend.org.dto.RenameClassRequest;
import com.qstory.backend.org.dto.TermTransitionRequest;
import com.qstory.backend.org.dto.TermTransitionResponse;
import com.qstory.backend.org.service.ClassLifecycleService;
import com.qstory.backend.org.service.ClassService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Classes", description = "Organization classes - homeroom tutor, student roster, join codes, and parent enrollment")
@RestController
public class ClassController {

    private final ClassService service;
    private final ClassLifecycleService lifecycleService;
    private final CurrentUserResolver currentUserResolver;

    public ClassController(
            ClassService service, ClassLifecycleService lifecycleService, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.lifecycleService = lifecycleService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Create a class", description = "DIRECTOR of the organization only. homeroomTutorId is optional; without it the class has no homeroom tutor until one is assigned.")
    @PostMapping("/v1/organizations/{orgId}/classes")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassResponse create(@PathVariable UUID orgId, @RequestBody CreateClassRequest request) {
        return service.create(currentUserResolver.requireRole(Role.DIRECTOR), orgId, request);
    }

    @Operation(summary = "List an organization's classes",
            description = "DIRECTOR of the organization only. Archived (past) classes are excluded unless includeArchived=true.")
    @GetMapping("/v1/organizations/{orgId}/classes")
    public List<ClassResponse> list(
            @PathVariable UUID orgId, @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(currentUserResolver.requireRole(Role.DIRECTOR), orgId, includeArchived);
    }

    @Operation(summary = "Get a class",
            description = "The owning DIRECTOR, the class's homeroom TUTOR, or a former homeroom TUTOR still in the "
                    + "organization (joinCode is null for former homerooms).")
    @GetMapping("/v1/classes/{classId}")
    public ClassResponse get(@PathVariable UUID classId) {
        return service.get(currentUserResolver.require(), classId);
    }

    @Operation(summary = "Rename a class",
            description = "Owning DIRECTOR only. Body {name}. Archived classes can be renamed too. Past reports keep the "
                    + "class name from when they were recorded.")
    @PatchMapping("/v1/classes/{classId}")
    public ClassResponse rename(@PathVariable UUID classId, @RequestBody RenameClassRequest request) {
        return lifecycleService.rename(currentUserResolver.requireRole(Role.DIRECTOR), classId, request);
    }

    @Operation(summary = "Archive a class (past class)",
            description = "Owning DIRECTOR only. 409 CLASS_HAS_ACTIVE_STUDENTS while the class still has current "
                    + "(non-graduated) students - move or graduate them first (term-transition). The join code and "
                    + "homeroom invites stop working (410). Records stay. Idempotent.")
    @PostMapping("/v1/classes/{classId}/archive")
    public ClassResponse archive(@PathVariable UUID classId) {
        return lifecycleService.archive(currentUserResolver.requireRole(Role.DIRECTOR), classId);
    }

    @Operation(summary = "Unarchive a class", description = "Owning DIRECTOR only. The join code works again. Idempotent.")
    @PostMapping("/v1/classes/{classId}/unarchive")
    public ClassResponse unarchive(@PathVariable UUID classId) {
        return lifecycleService.unarchive(currentUserResolver.requireRole(Role.DIRECTOR), classId);
    }

    @Operation(summary = "Move students to another class of the same organization",
            description = "Owning DIRECTOR only. Body {studentIds, targetClassId}. The same student row moves (history, "
                    + "parent link kept); the student becomes the target homeroom's student. Target in another "
                    + "organization 404, archived 409. Per-student failures come back in skipped.")
    @PostMapping("/v1/classes/{classId}/students/move")
    public MoveStudentsResponse moveStudents(@PathVariable UUID classId, @RequestBody MoveStudentsRequest request) {
        return lifecycleService.moveStudents(currentUserResolver.requireRole(Role.DIRECTOR), classId, request);
    }

    @Operation(summary = "Term transition",
            description = "Owning DIRECTOR only. Body {decisions:[{studentId, action: MOVE|KEEP|GRADUATE, targetClassId?}], "
                    + "archiveClass}. Every current student needs exactly one decision (400 otherwise). One transaction.")
    @PostMapping("/v1/classes/{classId}/term-transition")
    public TermTransitionResponse termTransition(
            @PathVariable UUID classId, @RequestBody TermTransitionRequest request) {
        return lifecycleService.termTransition(currentUserResolver.requireRole(Role.DIRECTOR), classId, request);
    }

    @Operation(summary = "List a student's class history",
            description = "The organization's DIRECTOR, or the student's current or former homeroom TUTOR. Oldest first.")
    @GetMapping("/v1/tutor-students/{studentId}/class-history")
    public List<ClassHistoryEntryResponse> classHistory(@PathVariable UUID studentId) {
        return lifecycleService.classHistory(currentUserResolver.require(), studentId);
    }

    @Operation(summary = "List the students in a class",
            description = "The owning DIRECTOR, the class's homeroom TUTOR, or a former homeroom (only students they "
                    + "taught). Graduated students are not current members; includePast=true appends students who left "
                    + "(moved or graduated) with endedAt/endReason.")
    @GetMapping("/v1/classes/{classId}/students")
    public List<ClassStudentResponse> listStudents(
            @PathVariable UUID classId, @RequestParam(defaultValue = "false") boolean includePast) {
        return service.listStudents(currentUserResolver.require(), classId, includePast);
    }

    @Operation(summary = "Assign or change the homeroom tutor",
            description = "Owning DIRECTOR only. The tutor must belong to the organization. The class's roster students "
                    + "become the new tutor's students and the previous tutor's not-yet-started (SCHEDULED) lessons of this "
                    + "class move to the new tutor. Past lessons and reports stay with the tutor who ran them.")
    @PutMapping("/v1/classes/{classId}/homeroom")
    public ClassResponse assignHomeroom(@PathVariable UUID classId, @RequestBody AssignHomeroomRequest request) {
        return service.assignHomeroom(currentUserResolver.requireRole(Role.DIRECTOR), classId, request.tutorId());
    }

    @Operation(summary = "List a class's homeroom history", description = "Owning DIRECTOR only. Oldest first; endedAt null is the current homeroom tutor.")
    @GetMapping("/v1/classes/{classId}/homeroom-history")
    public List<HomeroomHistoryEntryResponse> homeroomHistory(@PathVariable UUID classId) {
        return service.homeroomHistory(currentUserResolver.requireRole(Role.DIRECTOR), classId);
    }

    @Operation(summary = "List one class student's lesson reports",
            description = "The owning DIRECTOR sees every report the student took part in (including a previous homeroom "
                    + "tutor's); the homeroom TUTOR sees only the sessions they ran. Newest first.")
    @GetMapping("/v1/classes/{classId}/students/{studentId}/reports")
    public List<ClassStudentReportResponse> listStudentReports(@PathVariable UUID classId, @PathVariable UUID studentId) {
        return service.listStudentReports(currentUserResolver.require(), classId, studentId);
    }

    @Operation(summary = "List a class's recent lesson reports",
            description = "The owning DIRECTOR sees every tutor's reports of the class; the homeroom TUTOR only the sessions "
                    + "they ran. Home sessions are excluded. Newest first; limit defaults to 20, max 50.")
    @GetMapping("/v1/classes/{classId}/reports")
    public List<ClassReportResponse> listClassReports(
            @PathVariable UUID classId, @RequestParam(required = false) Integer limit) {
        return service.listClassReports(currentUserResolver.require(), classId, limit);
    }

    @Operation(summary = "Preview a class by its join code",
            description = "No authentication required. Class, organization, and homeroom tutor names for the class invite link.")
    @GetMapping("/v1/classes/by-code/{classCode}")
    public ClassPreviewResponse preview(@PathVariable String classCode) {
        return service.preview(classCode);
    }

    @Operation(summary = "List unlinked students of a class by its join code",
            description = "No authentication required. Names of roster students who have no parent yet, so a parent whose "
                    + "child name differs from the roster can pick their child (rosterStudentId on join).")
    @GetMapping("/v1/classes/by-code/{classCode}/roster")
    public List<ClassRosterEntryResponse> pendingRoster(@PathVariable String classCode) {
        return service.pendingRoster(classCode);
    }

    @Operation(summary = "Join a class as a parent (this is parent signup)",
            description = "No authentication required. Creates the parent account and adds the child to the class roster using the reusable class code.")
    @PostMapping("/v1/classes/join")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse join(@RequestBody JoinClassRequest request) {
        return service.join(request);
    }

    @Operation(summary = "Add a child to a class with an existing parent account",
            description = "PARENT only. Adds the child to the class roster using the class code; call once per child. childId links an existing child profile instead of childName/childBirthYear.")
    @PostMapping("/v1/classes/join-existing")
    public AuthResponse joinExisting(@RequestBody JoinExistingClassRequest request) {
        return service.joinExistingParent(currentUserResolver.requireRole(Role.PARENT), request);
    }

    @Operation(summary = "List the classes the caller's children are in", description = "PARENT only.")
    @GetMapping("/v1/classes/memberships")
    public List<ClassMembershipResponse> listMemberships() {
        return service.listMemberships(currentUserResolver.requireRole(Role.PARENT));
    }

    @Operation(summary = "Take a child out of a class", description = "PARENT only. Releases the parent link; past lesson records are kept.")
    @DeleteMapping("/v1/classes/memberships/{studentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveClass(@PathVariable UUID studentId) {
        service.leaveClass(currentUserResolver.requireRole(Role.PARENT), studentId);
    }
}
