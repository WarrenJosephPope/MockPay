package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, String> {

    Optional<PaymentMethod> findByIdAndMerchantId(String id, String merchantId);

    /**
     * Backs the dashboard's "last four digits" search.
     *
     * <p>PaymentIntent stores only a payment-method id, and the two are not mapped as a JPA
     * relationship, so a Specification cannot join across them. Resolving the ids first and
     * filtering on {@code paymentMethodId IN (...)} keeps the query honest and the entities
     * decoupled.
     */
    List<PaymentMethod> findByMerchantIdAndCardLast4(String merchantId, String cardLast4);
}
