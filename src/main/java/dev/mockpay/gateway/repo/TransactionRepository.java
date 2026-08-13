package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    List<Transaction> findByPaymentIntentIdOrderByCreatedAtAsc(String paymentIntentId);

    List<Transaction> findByMerchantIdOrderByCreatedAtDesc(String merchantId);
}
