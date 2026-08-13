package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    Optional<WebhookEvent> findByIdAndMerchantId(String id, String merchantId);

    List<WebhookEvent> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

    /** The dispatcher's claim query: everything due, oldest first. */
    List<WebhookEvent> findByStatusInAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
            List<WebhookEvent.Status> statuses, Instant now);
}
