package com.qstory.backend.org.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.dto.AssignHomeroomRequest;
import com.qstory.backend.org.dto.ClassMembershipResponse;
import com.qstory.backend.org.dto.ClassPreviewResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.dto.ClassStudentResponse;
import com.qstory.backend.org.dto.CreateClassRequest;
import com.qstory.backend.org.dto.JoinClassRequest;
import com.qstory.backend.org.dto.JoinExistingClassRequest;
import com.qstory.backend.org.service.ClassService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Classes", description = "Organization classes - homeroom tutor, student roster, join codes, and parent enrollment")
@RestController
public class ClassController {

    private final ClassService service;
    private final CurrentUserResolver currentUserResolver;

    public ClassController(ClassService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Create a class", description = "DIRECTOR of the organization only. homeroomTutorId is optional; without it the class has no homeroom tutor until one is assigned.")
    @PostMapping("/v1/organizations/{orgId}/classes")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassResponse create(@PathVariable UUID orgId, @RequestBody CreateClassRequest request) {
        return service.create(currentUserResolver.requireRole(Role.DIRECTOR), orgId, request);
    }

    @Operation(summary = "List an organization's classes", description = "DIRECTOR of the organization only.")
    @GetMapping("/v1/organizations/{orgId}/classes")
    public List<ClassResponse> list(@PathVariable UUID orgId) {
        return service.list(currentUserResolver.requireRole(Role.DIRECTOR), orgId);
    }

    @Operation(summary = "Get a class", description = "The owning DIRECTOR or the class's homeroom TUTOR.")
    @GetMapping("/v1/classes/{classId}")
    public ClassResponse get(@PathVariable UUID classId) {
        return service.get(currentUserResolver.require(), classId);
    }

    @Operation(summary = "List the students in a class", description = "The owning DIRECTOR or the class's homeroom TUTOR.")
    @GetMapping("/v1/classes/{classId}/students")
    public List<ClassStudentResponse> listStudents(@PathVariable UUID classId) {
        return service.listStudents(currentUserResolver.require(), classId);
    }

    @Operation(summary = "Assign the homeroom tutor", description = "Owning DIRECTOR only, and only while the class has no homeroom tutor. The tutor must belong to the organization. Students who joined before the assignment become the tutor's students.")
    @PutMapping("/v1/classes/{classId}/homeroom")
    public ClassResponse assignHomeroom(@PathVariable UUID classId, @RequestBody AssignHomeroomRequest request) {
        return service.assignHomeroom(currentUserResolver.requireRole(Role.DIRECTOR), classId, request.tutorId());
    }

    @Operation(summary = "Preview a class by its join code",
            description = "No authentication required. Class, organization, and homeroom tutor names for the class invite link.")
    @GetMapping("/v1/classes/by-code/{classCode}")
    public ClassPreviewResponse preview(@PathVariable String classCode) {
        return service.preview(classCode);
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
