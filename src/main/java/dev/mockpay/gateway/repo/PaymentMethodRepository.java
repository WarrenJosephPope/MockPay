package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, String> {

    Optional<PaymentMethod> findByIdAndMerchantId(String id, String merchantId);
}
