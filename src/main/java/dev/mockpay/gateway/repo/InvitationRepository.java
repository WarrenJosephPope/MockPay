package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvitationRepository extends JpaRepository<Invitation, String> {

    Optional<Invitation> findByToken(String token);

    List<Invitation> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

    Optional<Invitation> findByIdAndMerchantId(String id, String merchantId);
}
