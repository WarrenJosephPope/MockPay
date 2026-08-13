package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.Dispute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DisputeRepository extends JpaRepository<Dispute, String> {

    Optional<Dispute> findByIdAndMerchantId(String id, String merchantId);

    List<Dispute> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

    List<Dispute> findByPaymentIntentId(String paymentIntentId);
}
