package com.qstory.backend.payment.controller;

import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.payment.dto.ConfirmPaymentRequest;
import com.qstory.backend.payment.dto.CreatePaymentOrderRequest;
import com.qstory.backend.payment.dto.PaymentOrderResponse;
import com.qstory.backend.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments", description = "Server-verified Toss Payments subscription orders")
@RestController
public class PaymentController {
    private final PaymentService service;
    private final CurrentUserResolver currentUserResolver;

    public PaymentController(PaymentService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Create a subscription payment order")
    @PostMapping("/v1/payments/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentOrderResponse create(@RequestBody CreatePaymentOrderRequest request) {
        return service.create(currentUserResolver.require(), request);
    }

    @Operation(summary = "Confirm a Toss payment", description = "Amount and order ownership are verified server-side before access is granted.")
    @PostMapping("/v1/payments/confirm")
    public PaymentOrderResponse confirm(@RequestBody ConfirmPaymentRequest request) {
        return service.confirm(currentUserResolver.require(), request);
    }
}
