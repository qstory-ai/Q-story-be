package com.qstory.backend.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.payment.PaymentOrderStatus;
import com.qstory.backend.payment.PaymentOrderTarget;
import com.qstory.backend.payment.dto.ConfirmPaymentRequest;
import com.qstory.backend.payment.dto.PaymentHistoryItemResponse;
import com.qstory.backend.payment.entity.PaymentOrder;
import com.qstory.backend.payment.repository.PaymentOrderRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 결제 내역은 본인(관리자는 소속 기관) 결제만 보이고, 승인 응답의 영수증 URL을 저장해 함께 돌려준다. */
class PaymentServiceHistoryTest {

    private final PaymentOrderRepository paymentOrderRepository = mock(PaymentOrderRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final TutorStudentRepository tutorStudentRepository = mock(TutorStudentRepository.class);
    private final TossPaymentsClient tossPaymentsClient = mock(TossPaymentsClient.class);
    private final AppProperties config = mock(AppProperties.class);
    private final PaymentService service = new PaymentService(
            paymentOrderRepository, userRepository, organizationRepository, tutorStudentRepository,
            tossPaymentsClient, config);

    private final UUID orgId = UUID.randomUUID();

    private static PaymentOrder paidOrder(UUID userId, PaymentOrderTarget target, String receiptUrl) {
        Instant now = Instant.now();
        return PaymentOrder.builder()
                .orderId("qs_" + UUID.randomUUID())
                .user(AppUser.builder().id(userId).role(Role.PARENT).build())
                .target(target)
                .status(PaymentOrderStatus.PAID)
                .amount(9900)
                .orderName("Q-Story 보호자 이용권 (30일)")
                .paymentKey("secret_payment_key")
                .receiptUrl(receiptUrl)
                .paidAt(now)
                .accessExpiresAt(now.plusSeconds(86400L * 30))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Test
    void parentSeesOnlyTheirOwnPaidParentOrders() {
        CurrentUser parent = new CurrentUser(UUID.randomUUID(), Role.PARENT, null);
        PaymentOrder order = paidOrder(parent.userId(), PaymentOrderTarget.PARENT, "https://dashboard.tosspayments.com/receipt/x");
        when(paymentOrderRepository.findTop50ByUser_IdAndTargetAndStatusOrderByPaidAtDesc(
                parent.userId(), PaymentOrderTarget.PARENT, PaymentOrderStatus.PAID)).thenReturn(List.of(order));

        List<PaymentHistoryItemResponse> history = service.history(parent);

        assertEquals(1, history.size());
        assertEquals(order.getOrderId(), history.get(0).orderId());
        assertEquals("PAID", history.get(0).status());
        assertEquals(9900, history.get(0).amount());
        assertEquals("https://dashboard.tosspayments.com/receipt/x", history.get(0).receiptUrl());
        verify(paymentOrderRepository, never())
                .findTop50ByOrganization_IdAndTargetAndStatusOrderByPaidAtDesc(any(), any(), any());
    }

    @Test
    void directorSeesTheOrganizationsPaidOrders() {
        CurrentUser director = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, orgId);
        // 같은 기관의 다른 관리자가 결제한 주문도 기관 결제 내역에 들어간다.
        PaymentOrder byOtherDirector = paidOrder(UUID.randomUUID(), PaymentOrderTarget.ORGANIZATION, null);
        when(paymentOrderRepository.findTop50ByOrganization_IdAndTargetAndStatusOrderByPaidAtDesc(
                orgId, PaymentOrderTarget.ORGANIZATION, PaymentOrderStatus.PAID)).thenReturn(List.of(byOtherDirector));

        List<PaymentHistoryItemResponse> history = service.history(director);

        assertEquals(1, history.size());
        assertNull(history.get(0).receiptUrl());
        verify(paymentOrderRepository, never())
                .findTop50ByUser_IdAndTargetAndStatusOrderByPaidAtDesc(any(), any(), any());
    }

    @Test
    void directorWithoutOrganizationGetsAnEmptyList() {
        CurrentUser director = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, null);

        assertTrue(service.history(director).isEmpty());
        verifyNoInteractions(paymentOrderRepository);
    }

    @Test
    void tutorOnlySeesTheirOwnParentOrdersNeverTheOrganizations() {
        CurrentUser tutor = new CurrentUser(UUID.randomUUID(), Role.TUTOR, orgId);
        when(paymentOrderRepository.findTop50ByUser_IdAndTargetAndStatusOrderByPaidAtDesc(
                tutor.userId(), PaymentOrderTarget.PARENT, PaymentOrderStatus.PAID)).thenReturn(List.of());

        assertTrue(service.history(tutor).isEmpty());
        verify(paymentOrderRepository, never())
                .findTop50ByOrganization_IdAndTargetAndStatusOrderByPaidAtDesc(any(), any(), any());
    }

    @Test
    void confirmStoresTheReceiptUrlFromTheApproval() {
        CurrentUser parent = new CurrentUser(UUID.randomUUID(), Role.PARENT, null);
        when(config.payments()).thenReturn(new AppProperties.Payments(new AppProperties.Toss("secret", 9900, 0, 30)));
        AppUser user = AppUser.builder().id(parent.userId()).role(Role.PARENT).build();
        Instant now = Instant.now();
        PaymentOrder order = PaymentOrder.builder()
                .orderId("qs_receipt")
                .user(user)
                .target(PaymentOrderTarget.PARENT)
                .status(PaymentOrderStatus.READY)
                .amount(9900)
                .orderName("Q-Story 보호자 이용권 (30일)")
                .createdAt(now)
                .updatedAt(now)
                .build();
        when(paymentOrderRepository.findForUpdateByOrderId("qs_receipt")).thenReturn(Optional.of(order));
        when(tossPaymentsClient.confirm(anyString(), anyString(), anyInt()))
                .thenReturn(new TossPaymentsClient.Approval(now, "https://dashboard.tosspayments.com/receipt/r"));

        service.confirm(parent, new ConfirmPaymentRequest("pay_key", "qs_receipt", 9900));

        assertEquals("https://dashboard.tosspayments.com/receipt/r", order.getReceiptUrl());
        assertEquals(PaymentOrderStatus.PAID, order.getStatus());
    }

    @Test
    void receiptUrlIsReadFromTheTossResponseOnlyWhenItIsHttps() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("https://dashboard.tosspayments.com/receipt/a", TossPaymentsClient.receiptUrl(
                mapper.readTree("{\"receipt\":{\"url\":\"https://dashboard.tosspayments.com/receipt/a\"}}")));
        assertNull(TossPaymentsClient.receiptUrl(mapper.readTree("{\"receipt\":null}")));
        assertNull(TossPaymentsClient.receiptUrl(mapper.readTree("{}")));
        assertNull(TossPaymentsClient.receiptUrl(mapper.readTree("{\"receipt\":{\"url\":\"javascript:alert(1)\"}}")));
    }
}
