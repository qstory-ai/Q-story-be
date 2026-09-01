package com.qstory.backend.parent.child.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.parent.child.dto.ChildResponse;
import com.qstory.backend.parent.child.dto.CreateChildRequest;
import com.qstory.backend.parent.child.dto.UpdateChildRequest;
import com.qstory.backend.parent.child.service.ChildService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Parent children", description = "Parent-owned child profiles (multi-child selector)")
@RestController
@RequestMapping("/v1/parents/me/children")
public class ParentChildrenController {

    private final ChildService service;
    private final CurrentUserResolver currentUserResolver;

    public ParentChildrenController(ChildService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "List my children", description = "PARENT only. Empty list for a newly-signed-up parent.")
    @GetMapping
    public List<ChildResponse> list() {
        return service.listMine(currentUserResolver.requireRole(Role.PARENT));
    }

    @Operation(summary = "Register a new child profile", description = "PARENT only.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChildResponse create(@RequestBody CreateChildRequest request) {
        return service.create(currentUserResolver.requireRole(Role.PARENT), request);
    }

    @Operation(summary = "Update a child profile", description = "PARENT only. Must own the child.")
    @PatchMapping("/{childId}")
    public ChildResponse update(@PathVariable UUID childId, @RequestBody UpdateChildRequest request) {
        return service.update(currentUserResolver.requireRole(Role.PARENT), childId, request);
    }

    @Operation(summary = "Delete a child profile", description = "PARENT only. Must own the child.")
    @DeleteMapping("/{childId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID childId) {
        service.delete(currentUserResolver.requireRole(Role.PARENT), childId);
    }
}
