package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, String> {

    Optional<Settlement> findByIdAndMerchantId(String id, String merchantId);

    List<Settlement> findByMerchantIdOrderByCreatedAtDesc(String merchantId);
}
