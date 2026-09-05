package com.qstory.backend.payment.dto;

import com.qstory.backend.payment.PaymentOrderTarget;

public record CreatePaymentOrderRequest(PaymentOrderTarget target) {}
