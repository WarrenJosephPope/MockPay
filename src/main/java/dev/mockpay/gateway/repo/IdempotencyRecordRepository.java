package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    List<IdempotencyRecord> findByCreatedAtBefore(Instant cutoff);
}
