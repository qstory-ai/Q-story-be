package com.qstory.backend.payment.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.SubscriptionStatus;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.payment.PaymentOrderStatus;
import com.qstory.backend.payment.PaymentOrderTarget;
import com.qstory.backend.payment.dto.ConfirmPaymentRequest;
import com.qstory.backend.payment.dto.CreatePaymentOrderRequest;
import com.qstory.backend.payment.dto.PaymentOrderResponse;
import com.qstory.backend.payment.entity.PaymentOrder;
import com.qstory.backend.payment.repository.PaymentOrderRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private final PaymentOrderRepository paymentOrderRepository;
    private final AppUserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final TossPaymentsClient tossPaymentsClient;
    private final AppProperties config;

    public PaymentService(
            PaymentOrderRepository paymentOrderRepository,
            AppUserRepository userRepository,
            OrganizationRepository organizationRepository,
            TossPaymentsClient tossPaymentsClient,
            AppProperties config) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.tossPaymentsClient = tossPaymentsClient;
        this.config = config;
    }

    @Transactional
    public PaymentOrderResponse create(CurrentUser caller, CreatePaymentOrderRequest request) {
        if (request == null || request.target() == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "결제할 이용권을 선택해 주세요.");
        }
        requirePaymentConfigured();
        AppUser user = userRepository.findByIdAndDeletedAtIsNull(caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
        PaymentOrderTarget target = request.target();
        Organization organization = null;
        int amount;
        String orderName;
        if (target == PaymentOrderTarget.PARENT) {
            if (caller.role() != Role.PARENT) {
                throw ApiException.contractError(ErrorCode.FORBIDDEN, "보호자 이용권은 보호자 계정에서만 결제할 수 있어요.", 403);
            }
            amount = config.payments().toss().parentMonthlyAmount();
            orderName = "Q-Story 보호자 이용권 (30일)";
        } else {
            if (caller.role() != Role.DIRECTOR || caller.orgId() == null) {
                throw ApiException.contractError(ErrorCode.FORBIDDEN, "기관 이용권은 기관 관리자만 결제할 수 있어요.", 403);
            }
            organization = organizationRepository.findById(caller.orgId())
                    .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "기관을 찾을 수 없어요.", 404));
            amount = config.payments().toss().organizationMonthlyAmount();
            orderName = "Q-Story 기관 이용권 (30일)";
        }
        if (amount <= 0) {
            throw ApiException.contractError(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "결제 금액 설정을 확인해 주세요.", 503);
        }
        Instant now = Instant.now();
        PaymentOrder order = paymentOrderRepository.save(PaymentOrder.builder()
                .orderId("qs_" + UUID.randomUUID().toString().replace("-", ""))
                .user(user)
                .organization(organization)
                .target(target)
                .status(PaymentOrderStatus.READY)
                .amount(amount)
                .orderName(orderName)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return PaymentOrderResponse.of(order);
    }

    @Transactional
    public PaymentOrderResponse confirm(CurrentUser caller, ConfirmPaymentRequest request) {
        if (request == null || isBlank(request.paymentKey()) || isBlank(request.orderId()) || request.amount() <= 0) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "결제 확인 정보가 올바르지 않아요.");
        }
        PaymentOrder order = paymentOrderRepository.findForUpdateByOrderId(request.orderId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "결제 주문을 찾을 수 없어요.", 404));
        if (!order.getUser().getId().equals(caller.userId())) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 결제 주문을 확인할 권한이 없어요.", 403);
        }
        if (order.getStatus() == PaymentOrderStatus.PAID) {
            return PaymentOrderResponse.of(order);
        }
        if (order.getStatus() != PaymentOrderStatus.READY || order.getAmount() != request.amount()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "결제 금액 또는 주문 상태가 올바르지 않아요.", 409);
        }

        TossPaymentsClient.Approval approval = tossPaymentsClient.confirm(
                request.paymentKey(), order.getOrderId(), order.getAmount());
        Instant paidAt = approval.approvedAt();
        Instant base = paidAt;
        if (order.getTarget() == PaymentOrderTarget.PARENT) {
            AppUser user = order.getUser();
            if (user.getSubscriptionExpiresAt() != null && user.getSubscriptionExpiresAt().isAfter(base)) {
                base = user.getSubscriptionExpiresAt();
            }
            Instant expiresAt = base.plus(accessDuration());
            user.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
            user.setSubscriptionUpdatedAt(paidAt);
            user.setSubscriptionExpiresAt(expiresAt);
            userRepository.save(user);
            order.setAccessExpiresAt(expiresAt);
        } else {
            Organization organization = order.getOrganization();
            if (organization == null) {
                throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "기관 결제 주문의 기관 정보가 없어요.", 500);
            }
            if (organization.getSubscriptionExpiresAt() != null && organization.getSubscriptionExpiresAt().isAfter(base)) {
                base = organization.getSubscriptionExpiresAt();
            }
            Instant expiresAt = base.plus(accessDuration());
            organization.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
            organization.setSubscriptionUpdatedAt(paidAt);
            organization.setSubscriptionExpiresAt(expiresAt);
            organizationRepository.save(organization);
            order.setAccessExpiresAt(expiresAt);
        }
        order.setStatus(PaymentOrderStatus.PAID);
        order.setPaymentKey(request.paymentKey());
        order.setPaidAt(paidAt);
        order.setUpdatedAt(Instant.now());
        paymentOrderRepository.save(order);
        return PaymentOrderResponse.of(order);
    }

    private Duration accessDuration() {
        int accessDays = config.payments().toss().accessDays();
        return Duration.ofDays(Math.max(1, accessDays));
    }

    private void requirePaymentConfigured() {
        if (config.payments() == null || config.payments().toss() == null || !config.payments().toss().configured()) {
            throw ApiException.contractError(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "결제 설정이 아직 준비되지 않았어요.", 503);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
