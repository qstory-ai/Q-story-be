package com.qstory.backend.identity.controller;

import com.qstory.backend.identity.dto.ConsentRecordRequest;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.identity.service.ConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Consent", description = "Consent history")
@RestController
public class ConsentController {

    private final ConsentService consentService;
    private final CurrentUserResolver currentUserResolver;

    public ConsentController(ConsentService consentService, CurrentUserResolver currentUserResolver) {
        this.consentService = consentService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Record consent decisions",
            description = "Any signed-in user. Appends one history row per item (agree or withdraw).")
    @PostMapping("/v1/me/consents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@RequestBody ConsentRecordRequest request) {
        consentService.record(currentUserResolver.require(), request);
    }
}
