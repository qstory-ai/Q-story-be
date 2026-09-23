package com.qstory.backend.org.tutor.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.tutor.service.OrganizationTutorInsightService;
import com.qstory.backend.tutor.dto.TutorStudentResponse;
import com.qstory.backend.tutor.lesson.dto.LessonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 기관 관리자가 소속 선생님의 학생·수업을 읽는 엔드포인트. 쓰기는 없다 - 학생·수업의 주인은 선생님이다. */
@Tag(name = "OrganizationTutorInsight", description = "What an organization can see about its linked tutors' students and lessons")
@RestController
public class OrganizationTutorInsightController {

    private final OrganizationTutorInsightService service;
    private final CurrentUserResolver currentUserResolver;

    public OrganizationTutorInsightController(
            OrganizationTutorInsightService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "List a linked tutor's students",
            description = "DIRECTOR of that organization only; the tutor must be linked via organization_tutor. "
                    + "Returns every student the tutor manages (the organization sees all of them by product decision).")
    @GetMapping("/v1/organizations/{organizationId}/tutors/{tutorId}/students")
    public List<TutorStudentResponse> listStudents(@PathVariable UUID organizationId, @PathVariable UUID tutorId) {
        return service.listStudents(currentUserResolver.requireRole(Role.DIRECTOR), organizationId, tutorId);
    }

    @Operation(summary = "List a linked tutor's lessons",
            description = "DIRECTOR of that organization only; same scope rule as the students endpoint.")
    @GetMapping("/v1/organizations/{organizationId}/tutors/{tutorId}/lessons")
    public List<LessonResponse> listLessons(@PathVariable UUID organizationId, @PathVariable UUID tutorId) {
        return service.listLessons(currentUserResolver.requireRole(Role.DIRECTOR), organizationId, tutorId);
    }
}
