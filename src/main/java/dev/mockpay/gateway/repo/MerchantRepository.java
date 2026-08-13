package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, String> {

    // Key lookups moved to ApiKeyRepository.findByKeyHash — a merchant is no longer reachable
    // from a credential, only the other way round.
}
