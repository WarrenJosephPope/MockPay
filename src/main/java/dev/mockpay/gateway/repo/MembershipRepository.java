package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.Membership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MembershipRepository extends JpaRepository<Membership, String> {

    /** The authorisation lookup: does this user have any authority over this account, and how much? */
    Optional<Membership> findByUserIdAndMerchantId(String userId, String merchantId);

    List<Membership> findByUserId(String userId);

    List<Membership> findByMerchantId(String merchantId);

    long countByMerchantIdAndRole(String merchantId, Membership.Role role);
}
