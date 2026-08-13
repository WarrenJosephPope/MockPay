package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {

    /**
     * The authentication hot path: one indexed equality lookup, on every request.
     *
     * <p>This is why keys are hashed with SHA-256 rather than bcrypt — a slow hash would force a
     * scan-and-compare across every row, since you cannot index a value you have to recompute per
     * candidate.
     */
    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

    Optional<ApiKey> findByIdAndMerchantId(String id, String merchantId);

    List<ApiKey> findByMerchantIdAndTypeAndRevokedAtIsNull(String merchantId, ApiKey.Type type);
}
