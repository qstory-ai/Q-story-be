package com.qstory.backend.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
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
import com.qstory.backend.payment.dto.PaymentOrderResponse;
import com.qstory.backend.payment.entity.PaymentOrder;
import com.qstory.backend.payment.repository.PaymentOrderRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 기관 이용권은 결제 시점의 학생 수 x 학생당 금액이고, 결제한 학생 수가 이용 인원으로 남는다. */
class PaymentServiceOrganizationTest {

    private static final int UNIT = 1000;

    private final PaymentOrderRepository paymentOrderRepository = mock(PaymentOrderRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final TutorStudentRepository tutorStudentRepository = mock(TutorStudentRepository.class);
    private final TossPaymentsClient tossPaymentsClient = mock(TossPaymentsClient.class);
    private final AppProperties config = mock(AppProperties.class);

    private final UUID orgId = UUID.randomUUID();
    private final CurrentUser director = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, orgId);
    private final Organization organization = Organization.builder().id(orgId).name("유치원").build();

    private PaymentService serviceWithUnit(int unitAmount) {
        when(config.payments()).thenReturn(new AppProperties.Payments(
                new AppProperties.Toss("secret", 9900, unitAmount, 30)));
        when(userRepository.findByIdAndDeletedAtIsNull(director.userId()))
                .thenReturn(Optional.of(AppUser.builder().id(director.userId()).role(Role.DIRECTOR).build()));
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(paymentOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new PaymentService(
                paymentOrderRepository, userRepository, organizationRepository, tutorStudentRepository,
                tossPaymentsClient, config);
    }

    private static CreatePaymentOrderRequest organizationOrder() {
        return new CreatePaymentOrderRequest(PaymentOrderTarget.ORGANIZATION);
    }

    @Test
    void orderAmountIsStudentCountTimesUnitAmount() {
        PaymentService service = serviceWithUnit(UNIT);
        when(tutorStudentRepository.countByClassGroup_Organization_IdAndDeletedAtIsNull(orgId)).thenReturn(12L);

        PaymentOrderResponse order = service.create(director, organizationOrder());

        assertEquals(12 * UNIT, order.amount());
        assertEquals(true, order.orderName().contains("12명"));
    }

    @Test
    void orderIsRefusedWhenThePerStudentAmountIsNotConfigured() {
        PaymentService service = serviceWithUnit(0);
        when(tutorStudentRepository.countByClassGroup_Organization_IdAndDeletedAtIsNull(orgId)).thenReturn(12L);
        assertThrows(ApiException.class, () -> service.create(director, organizationOrder()));
    }

    @Test
    void orderIsRefusedWhenThereAreNoStudents() {
        PaymentService service = serviceWithUnit(UNIT);
        when(tutorStudentRepository.countByClassGroup_Organization_IdAndDeletedAtIsNull(orgId)).thenReturn(0L);
        assertThrows(ApiException.class, () -> service.create(director, organizationOrder()));
    }

    @Test
    void quoteShowsCurrentStudentsAndPaidSeats() {
        PaymentService service = serviceWithUnit(UNIT);
        organization.setSubscriptionSeats(8);
        when(tutorStudentRepository.countByClassGroup_Organization_IdAndDeletedAtIsNull(orgId)).thenReturn(10L);

        OrganizationQuoteResponse quote = service.quoteOrganization(director);

        assertEquals(10, quote.studentCount());
        assertEquals(UNIT, quote.unitAmount());
        assertEquals(10 * UNIT, quote.amount());
        assertEquals(8, quote.currentSeats());
        assertEquals(30, quote.accessDays());
    }

    @Test
    void confirmedPaymentRecordsThePaidSeatsOnTheOrganization() {
        PaymentService service = serviceWithUnit(UNIT);
        PaymentOrder order = PaymentOrder.builder()
                .orderId("qs_test")
                .user(AppUser.builder().id(director.userId()).role(Role.DIRECTOR).build())
                .organization(organization)
                .target(PaymentOrderTarget.ORGANIZATION)
                .status(PaymentOrderStatus.READY)
                .amount(12 * UNIT)
                .studentCount(12)
                .orderName("test")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(paymentOrderRepository.findForUpdateByOrderId("qs_test")).thenReturn(Optional.of(order));
        when(tossPaymentsClient.confirm(anyString(), anyString(), anyInt()))
                .thenReturn(new TossPaymentsClient.Approval(Instant.now()));
        assertNull(organization.getSubscriptionSeats());

        service.confirm(director, new ConfirmPaymentRequest("pay_key", "qs_test", 12 * UNIT));

        assertEquals(12, organization.getSubscriptionSeats());
        assertEquals(SubscriptionStatus.ACTIVE, organization.getSubscriptionStatus());
    }
}
