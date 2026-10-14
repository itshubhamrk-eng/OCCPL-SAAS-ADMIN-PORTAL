package com.occpl.platform.billing.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    boolean existsByGatewayPaymentIdAndStatus(String gatewayPaymentId, String status);

    List<PaymentTransaction> findByInvoiceIdOrderByOccurredAt(UUID invoiceId);
}
