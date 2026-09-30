package com.qstory.backend.payment.repository;

import com.qstory.backend.payment.entity.PaymentOrder;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select paymentOrder from PaymentOrder paymentOrder where paymentOrder.orderId = :orderId")
    Optional<PaymentOrder> findForUpdateByOrderId(String orderId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("update PaymentOrder paymentOrder set paymentOrder.status = com.qstory.backend.payment.PaymentOrderStatus.FAILED, "
            + "paymentOrder.updatedAt = :now where paymentOrder.organization.id = :organizationId "
            + "and paymentOrder.status = com.qstory.backend.payment.PaymentOrderStatus.READY")
    int voidOpenOrganizationOrders(
            @org.springframework.data.repository.query.Param("organizationId") java.util.UUID organizationId,
            @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
