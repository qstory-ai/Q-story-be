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
import com.qstory.backend.payment.dto.OrganizationQuoteResponse;
import com.qstory.backend.payment.dto.PaymentHistoryItemResponse;
import com.qstory.backend.payment.dto.PaymentOrderResponse;
import com.qstory.backend.payment.entity.PaymentOrder;
import com.qstory.backend.payment.repository.PaymentOrderRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private final PaymentOrderRepository paymentOrderRepository;
    private final AppUserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final TutorStudentRepository tutorStudentRepository;
    private final TossPaymentsClient tossPaymentsClient;
    private final AppProperties config;

    public PaymentService(
            PaymentOrderRepository paymentOrderRepository,
            AppUserRepository userRepository,
            OrganizationRepository organizationRepository,
            TutorStudentRepository tutorStudentRepository,
            TossPaymentsClient tossPaymentsClient,
            AppProperties config) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.tutorStudentRepository = tutorStudentRepository;
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
        int unitAmount;
        Integer studentCount = null;
        String orderName;
        if (target == PaymentOrderTarget.PARENT) {
            if (caller.role() != Role.PARENT) {
                throw ApiException.contractError(ErrorCode.FORBIDDEN, "보호자 이용권은 보호자 계정에서만 결제할 수 있어요.", 403);
            }
            amount = config.payments().toss().parentMonthlyAmount();
            unitAmount = amount;
            orderName = "Q-Story 보호자 이용권 (30일)";
        } else {
            organization = requireDirectorOrganization(caller, "기관 이용권은 기관 관리자만 결제할 수 있어요.");
            unitAmount = config.payments().toss().organizationStudentMonthlyAmount();
            studentCount = (int) billableStudentCount(caller.orgId());
            if (studentCount <= 0) {
                throw ApiException.contractError(
                        ErrorCode.VALIDATION_FAILED, "결제할 학생이 없어요. 학부모가 연결된 학생이 생긴 뒤 결제해 주세요.");
            }
            amount = unitAmount * studentCount;
            orderName = "Q-Story 기관 이용권 (학생 " + studentCount + "명 · " + accessDuration().toDays() + "일)";
        }
        if (amount <= 0) {
            throw ApiException.contractError(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "결제 금액 설정을 확인해 주세요.", 503);
        }
        Instant now = Instant.now();
        if (organization != null) {
            // 인원이 다른 미결제 주문이 뒤늦게 승인돼 인원을 되돌리지 않게, 새 주문을 만들면 이전 미결제 주문은 닫는다.
            paymentOrderRepository.voidOpenOrganizationOrders(organization.getId(), now);
        }
        PaymentOrder order = paymentOrderRepository.save(PaymentOrder.builder()
                .orderId("qs_" + UUID.randomUUID().toString().replace("-", ""))
                .user(user)
                .organization(organization)
                .target(target)
                .status(PaymentOrderStatus.READY)
                .amount(amount)
                .studentCount(studentCount)
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
            boolean stillActive = organization.getSubscriptionStatus()
                    .grantsAccessAt(organization.getSubscriptionExpiresAt(), paidAt);
            if (organization.getSubscriptionExpiresAt() != null && organization.getSubscriptionExpiresAt().isAfter(base)) {
                base = organization.getSubscriptionExpiresAt();
            }
            Instant expiresAt = base.plus(accessDuration());
            organization.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
            organization.setSubscriptionUpdatedAt(paidAt);
            organization.setSubscriptionExpiresAt(expiresAt);
            organization.setSubscriptionSeats(seatsAfterPayment(organization, order.getStudentCount(), stillActive));
            organizationRepository.save(organization);
            order.setAccessExpiresAt(expiresAt);
        }
        order.setStatus(PaymentOrderStatus.PAID);
        order.setPaymentKey(request.paymentKey());
        order.setReceiptUrl(approval.receiptUrl());
        order.setPaidAt(paidAt);
        order.setUpdatedAt(Instant.now());
        paymentOrderRepository.save(order);
        return PaymentOrderResponse.of(order);
    }

    /**
     * 결제 후 이용 인원. 구독이 유효한 중에 연장 결제하면 남은 기간도 새 인원으로 바뀌므로 기존보다 줄이지 않는다
     * (100명분 사용 중에 10명분을 연장해도 남은 기간의 90명이 이용권을 잃지 않게). 인원 기록이 없는 예전 정액
     * 구독(null)은 유효한 동안 제한 없음을 유지한다. 만료 뒤 새로 결제하면 그 결제의 인원으로 시작한다.
     */
    private static Integer seatsAfterPayment(Organization organization, Integer paidSeats, boolean stillActive) {
        if (!stillActive) return paidSeats;
        Integer current = organization.getSubscriptionSeats();
        if (current == null || paidSeats == null) return null;
        return Math.max(current, paidSeats);
    }

    /**
     * 결제 내역 - 승인까지 끝난(PAID) 주문만 최근 순으로. 결제창을 열었다가 닫은 READY 주문과
     * 새 주문에 밀려 닫힌 FAILED 주문은 돈이 오가지 않았으므로 보여 주지 않는다.
     * 관리자(DIRECTOR)는 소속 기관의 기관 이용권 결제 전체(다른 관리자가 결제한 것 포함),
     * 그 외 역할은 본인이 결제한 보호자 이용권만 본다. 소속 기관이 없는 관리자는 빈 목록.
     */
    @Transactional(readOnly = true)
    public List<PaymentHistoryItemResponse> history(CurrentUser caller) {
        List<PaymentOrder> orders;
        if (caller.role() == Role.DIRECTOR) {
            if (caller.orgId() == null) return List.of();
            orders = paymentOrderRepository.findTop50ByOrganization_IdAndTargetAndStatusOrderByPaidAtDesc(
                    caller.orgId(), PaymentOrderTarget.ORGANIZATION, PaymentOrderStatus.PAID);
        } else {
            orders = paymentOrderRepository.findTop50ByUser_IdAndTargetAndStatusOrderByPaidAtDesc(
                    caller.userId(), PaymentOrderTarget.PARENT, PaymentOrderStatus.PAID);
        }
        return orders.stream().map(PaymentHistoryItemResponse::of).toList();
    }

    /** 결제 화면에 미리 보여 줄 견적 - 과금 대상 학생 수, 명단 전체 학생 수, 학생당 금액, 합계, 지금 결제된 인원. */
    @Transactional(readOnly = true)
    public OrganizationQuoteResponse quoteOrganization(CurrentUser caller) {
        Organization organization = requireDirectorOrganization(caller, "기관 이용권은 기관 관리자만 볼 수 있어요.");
        int studentCount = (int) billableStudentCount(caller.orgId());
        int rosterStudentCount = (int) tutorStudentRepository.countByClassGroup_Organization_IdAndDeletedAtIsNull(caller.orgId());
        int unitAmount = Math.max(0, config.payments().toss().organizationStudentMonthlyAmount());
        return new OrganizationQuoteResponse(
                studentCount, rosterStudentCount, unitAmount, unitAmount * studentCount, organization.getSubscriptionSeats(),
                Math.max(1, config.payments().toss().accessDays()));
    }

    /** 과금 대상 = 기관 반에서 학부모가 연결된 학생(이용권 혜택을 실제로 받는 학생). */
    private long billableStudentCount(UUID organizationId) {
        return tutorStudentRepository.countByClassGroup_Organization_IdAndDeletedAtIsNullAndLinkedParentUserIsNotNull(organizationId);
    }

    private Organization requireDirectorOrganization(CurrentUser caller, String forbiddenMessage) {
        if (caller.role() != Role.DIRECTOR || caller.orgId() == null) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, forbiddenMessage, 403);
        }
        return organizationRepository.findById(caller.orgId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "기관을 찾을 수 없어요.", 404));
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
