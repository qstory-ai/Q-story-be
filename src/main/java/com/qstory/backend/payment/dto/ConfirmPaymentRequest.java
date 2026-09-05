package com.qstory.backend.payment.dto;

public record ConfirmPaymentRequest(String paymentKey, String orderId, int amount) {}
