package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, String> {

    Optional<Merchant> findBySecretKey(String secretKey);

    Optional<Merchant> findByPublishableKey(String publishableKey);
}
