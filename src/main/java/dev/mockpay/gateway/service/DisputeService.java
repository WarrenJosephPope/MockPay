package dev.mockpay.gateway.service;

import dev.mockpay.gateway.domain.Dispute;
import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.repo.DisputeRepository;
import dev.mockpay.gateway.support.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chargebacks.
 *
 * <p>The economics are worth stating plainly, because they drive most anti-fraud spending: a lost
 * dispute costs the merchant the goods, the sale amount, and a non-refundable fee. Winning costs
 * them the fee anyway. So the cheapest dispute is the one that never happens, which is why
 * merchants care so much about clear statement descriptors and prompt refunds — a customer who
 * recognises the charge and can get their money back easily does not call their bank.
 *
 * <p>The lifecycle modelled here is Visa's post-VCR structure: a dispute is <em>allocated</em>
 * (fraud and authorisation cases, where the network decides liability from data it already holds) or
 * <em>collaborated</em> (consumer and processing-error cases, argued between the parties). Either
 * way the merchant has a fixed window measured in days, and missing it loses by default.
 */
@Service
public class DisputeService {

    /** The reason codes a merchant will actually encounter, with their real categories. */
    public record ReasonCode(String code, String description, String category, int responseDays) {
    }

    private static final List<ReasonCode> REASON_CODES = List.of(
            new ReasonCode("10.4", "Other Fraud - Card Absent Environment", "fraud", 30),
            new ReasonCode("10.1", "EMV Liability Shift Counterfeit Fraud", "fraud", 30),
            new ReasonCode("11.3", "No Authorization", "authorization", 30),
            new ReasonCode("12.5", "Incorrect Amount", "processing_error", 30),
            new ReasonCode("12.6", "Duplicate Processing", "processing_error", 30),
            new ReasonCode("13.1", "Merchandise/Services Not Received", "consumer_dispute", 30),
            new ReasonCode("13.3", "Not as Described or Defective Merchandise", "consumer_dispute", 30),
            new ReasonCode("13.6", "Credit Not Processed", "consumer_dispute", 30),
            new ReasonCode("13.7", "Cancelled Merchandise/Services", "consumer_dispute", 30));

    /** Charged whatever the outcome. This asymmetry is the point: disputes are meant to hurt. */
    private static final long DISPUTE_FEE_MINOR = 1500;

    private final DisputeRepository disputes;
    private final LedgerService ledger;
    private final EventService events;
    private final PaymentService payments;

    public DisputeService(DisputeRepository disputes, LedgerService ledger, EventService events,
                          PaymentService payments) {
        this.disputes = disputes;
        this.ledger = ledger;
        this.events = events;
        this.payments = payments;
    }

    public static List<ReasonCode> reasonCodes() {
        return REASON_CODES;
    }

    /**
     * Open a dispute. In production this arrives from the acquirer, not from an API call — the
     * merchant is told, never asked.
     */
    @Transactional
    public Dispute open(String merchantId, String intentId, String reasonCode, Long amount) {
        PaymentIntent intent = payments.mustFind(merchantId, intentId);

        if (intent.getStatus() != PaymentIntent.Status.SUCCEEDED) {
            throw new ApiException(400, "invalid_request_error", "payment_intent_unexpected_state",
                    "Only a captured payment can be disputed.");
        }

        ReasonCode reason = REASON_CODES.stream()
                .filter(r -> r.code().equals(reasonCode))
                .findFirst()
                .orElse(REASON_CODES.get(0));

        // Partial chargebacks exist, but the full amount is the common case.
        long disputed = amount == null ? intent.getAmountReceived() : amount;

        Dispute dispute = new Dispute(Ids.generate("dp"), intentId, merchantId, disputed,
                intent.getCurrency(), reason.code(), reason.description(), reason.category(),
                DISPUTE_FEE_MINOR, Instant.now().plus(Duration.ofDays(reason.responseDays())));
        disputes.save(dispute);

        // The money leaves the merchant's balance now, not when the case resolves.
        ledger.recordDisputeOpened(merchantId, intent.getCurrency(), dispute.getId(),
                disputed, DISPUTE_FEE_MINOR);
        events.emit(merchantId, "dispute.created", snapshot(dispute));
        return dispute;
    }

    /**
     * Submit evidence (a "dispute response", in Visa's current vocabulary).
     *
     * <p>What wins cases is boring and specific: proof of delivery to the billing address, the AVS
     * and CVV results from the original authorisation, a record of the customer using the service,
     * and evidence they were shown the terms. Narrative arguments lose.
     */
    @Transactional
    public Dispute submitEvidence(String merchantId, String disputeId, String evidenceJson) {
        Dispute dispute = mustFind(merchantId, disputeId);

        if (dispute.getStatus() != Dispute.Status.NEEDS_RESPONSE) {
            throw new ApiException(400, "invalid_request_error", "dispute_unexpected_state",
                    "Evidence can only be submitted while the dispute needs a response.");
        }
        if (Instant.now().isAfter(dispute.getEvidenceDueBy())) {
            throw new ApiException(400, "invalid_request_error", "dispute_window_closed",
                    "The evidence window closed on " + dispute.getEvidenceDueBy() + ".");
        }

        dispute.setEvidenceJson(evidenceJson);
        dispute.setStatus(Dispute.Status.UNDER_REVIEW);
        disputes.save(dispute);
        events.emit(merchantId, "dispute.updated", snapshot(dispute));
        return dispute;
    }

    /** Resolve a dispute. In production the issuer decides this, on their own timetable. */
    @Transactional
    public Dispute resolve(String merchantId, String disputeId, boolean merchantWins) {
        Dispute dispute = mustFind(merchantId, disputeId);

        if (dispute.getStatus() == Dispute.Status.WON || dispute.getStatus() == Dispute.Status.LOST) {
            throw new ApiException(400, "invalid_request_error", "dispute_unexpected_state",
                    "This dispute is already resolved.");
        }

        dispute.setStatus(merchantWins ? Dispute.Status.WON : Dispute.Status.LOST);
        dispute.setResolvedAt(Instant.now());
        disputes.save(dispute);

        if (merchantWins) {
            ledger.recordDisputeWon(merchantId, dispute.getCurrency(), dispute.getId(),
                    dispute.getAmount());
        } else {
            ledger.recordDisputeLost(merchantId, dispute.getCurrency(), dispute.getId(),
                    dispute.getAmount());
        }

        events.emit(merchantId, merchantWins ? "dispute.won" : "dispute.lost", snapshot(dispute));
        return dispute;
    }

    /** Accept liability without a fight — often the right call when the evidence is weak. */
    @Transactional
    public Dispute accept(String merchantId, String disputeId) {
        Dispute dispute = mustFind(merchantId, disputeId);
        dispute.setStatus(Dispute.Status.ACCEPTED);
        dispute.setResolvedAt(Instant.now());
        disputes.save(dispute);
        ledger.recordDisputeLost(merchantId, dispute.getCurrency(), dispute.getId(), dispute.getAmount());
        events.emit(merchantId, "dispute.closed", snapshot(dispute));
        return dispute;
    }

    public Dispute mustFind(String merchantId, String disputeId) {
        return disputes.findByIdAndMerchantId(disputeId, merchantId)
                .orElseThrow(() -> ApiException.notFound("dispute"));
    }

    public List<Dispute> list(String merchantId) {
        return disputes.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    public Map<String, Object> snapshot(Dispute dispute) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", dispute.getId());
        map.put("object", "dispute");
        map.put("payment_intent", dispute.getPaymentIntentId());
        map.put("amount", dispute.getAmount());
        map.put("currency", dispute.getCurrency());
        map.put("status", dispute.getStatus().name().toLowerCase());
        map.put("reason_code", dispute.getReasonCode());
        map.put("reason", dispute.getReasonDescription());
        map.put("category", dispute.getCategory());
        map.put("fee", dispute.getDisputeFee());
        map.put("evidence_due_by", dispute.getEvidenceDueBy().getEpochSecond());
        map.put("created", dispute.getCreatedAt().getEpochSecond());
        return map;
    }
}
