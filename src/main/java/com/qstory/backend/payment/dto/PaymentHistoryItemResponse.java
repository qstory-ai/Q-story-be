package com.qstory.backend.payment.dto;

import com.qstory.backend.payment.entity.PaymentOrder;
import java.time.Instant;

/** 결제 내역 한 줄. paymentKey 같은 내부 값은 내보내지 않는다. receiptUrl은 저장된 경우에만 있다. */
public record PaymentHistoryItemResponse(
        String orderId,
        String target,
        String status,
        int amount,
        String orderName,
        Instant paidAt,
        Instant accessExpiresAt,
        String receiptUrl) {
    public static PaymentHistoryItemResponse of(PaymentOrder order) {
        return new PaymentHistoryItemResponse(
                order.getOrderId(), order.getTarget().name(), order.getStatus().name(), order.getAmount(),
                order.getOrderName(), order.getPaidAt(), order.getAccessExpiresAt(), order.getReceiptUrl());
    }
}
