package dev.mockpay.gateway.repo;

import dev.mockpay.gateway.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, String> {

    List<LedgerEntry> findByRefIdOrderByCreatedAtAsc(String refId);

    List<LedgerEntry> findByJournalIdOrderByCreatedAtAsc(String journalId);

    List<LedgerEntry> findByMerchantIdOrderByCreatedAtDesc(String merchantId);
}
