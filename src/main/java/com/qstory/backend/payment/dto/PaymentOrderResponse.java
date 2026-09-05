package com.qstory.backend.payment.dto;

import com.qstory.backend.payment.entity.PaymentOrder;
import java.time.Instant;

public record PaymentOrderResponse(
        String orderId,
        String target,
        String status,
        int amount,
        String orderName,
        Instant accessExpiresAt) {
    public static PaymentOrderResponse of(PaymentOrder order) {
        return new PaymentOrderResponse(
                order.getOrderId(), order.getTarget().name(), order.getStatus().name(), order.getAmount(),
                order.getOrderName(), order.getAccessExpiresAt());
    }
}
