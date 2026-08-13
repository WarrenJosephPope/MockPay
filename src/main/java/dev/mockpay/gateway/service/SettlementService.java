package dev.mockpay.gateway.service;

import dev.mockpay.gateway.domain.Dispute;
import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.domain.Refund;
import dev.mockpay.gateway.domain.Settlement;
import dev.mockpay.gateway.rails.GatewayProperties;
import dev.mockpay.gateway.repo.DisputeRepository;
import dev.mockpay.gateway.repo.PaymentIntentRepository;
import dev.mockpay.gateway.repo.RefundRepository;
import dev.mockpay.gateway.repo.SettlementRepository;
import dev.mockpay.gateway.support.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turning a day of transactions into one bank transfer.
 *
 * <p>This is where the gap between "the customer paid" and "the merchant has the money" gets
 * explained. Card networks operate <b>multilateral deferred net settlement</b>: rather than moving
 * money per transaction, they accumulate a cycle's clearing records and compute each institution's
 * single net position against every other. Millions of transactions collapse into a handful of
 * wires between banks. It is enormously efficient and it is why settlement is a batch, not a stream.
 *
 * <p>On top of the network's cycle sits the acquirer's own hold — the T+2 in this configuration —
 * which exists because refunds and chargebacks arrive <em>after</em> the sale. Paying a merchant
 * instantly means paying out money that might have to be clawed back from a merchant who no longer
 * has it.
 *
 * <p>UPI is the counterexample worth noting: NPCI runs many settlement cycles a day, so Indian
 * merchants see money in hours rather than days.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementRepository settlements;
    private final PaymentIntentRepository intents;
    private final RefundRepository refunds;
    private final DisputeRepository disputes;
    private final LedgerService ledger;
    private final EventService events;
    private final GatewayProperties props;

    public SettlementService(SettlementRepository settlements, PaymentIntentRepository intents,
                             RefundRepository refunds, DisputeRepository disputes,
                             LedgerService ledger, EventService events, GatewayProperties props) {
        this.settlements = settlements;
        this.intents = intents;
        this.refunds = refunds;
        this.disputes = disputes;
        this.ledger = ledger;
        this.events = events;
        this.props = props;
    }

    /**
     * Close a settlement period and compute the net position.
     *
     * <p>Exposed as an API call so a day's cycle can be triggered on demand rather than waited for.
     * In production this is a cron job that runs after the acquirer's cut-off time, and the cut-off
     * is a real deadline: a capture that misses it waits for the next cycle.
     */
    @Transactional
    public Settlement runBatch(String merchantId, String currency, LocalDate periodStart,
                               LocalDate periodEnd) {
        Instant from = periodStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = periodEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<PaymentIntent> captured = intents.findByMerchantIdAndStatusAndCapturedAtBetween(
                merchantId, PaymentIntent.Status.SUCCEEDED, from, to);

        long gross = 0;
        long fees = 0;
        int count = 0;
        for (PaymentIntent intent : captured) {
            if (!intent.getCurrency().equalsIgnoreCase(currency)) {
                // Each currency settles into its own account on its own cycle. Netting across
                // currencies would silently invent an exchange rate.
                continue;
            }
            gross += intent.getAmountReceived();
            fees += intent.getApplicationFee();
            count++;
        }

        long refunded = refunds
                .findByMerchantIdAndStatusAndCreatedAtBetween(merchantId, Refund.Status.SUCCEEDED, from, to)
                .stream()
                .filter(r -> r.getCurrency().equalsIgnoreCase(currency))
                .mapToLong(Refund::getAmount)
                .sum();

        long chargedBack = disputes.findByMerchantIdOrderByCreatedAtDesc(merchantId).stream()
                .filter(d -> d.getCurrency().equalsIgnoreCase(currency))
                .filter(d -> !d.getCreatedAt().isBefore(from) && d.getCreatedAt().isBefore(to))
                .filter(d -> d.getStatus() != Dispute.Status.WON)
                .mapToLong(d -> d.getAmount() + d.getDisputeFee())
                .sum();

        Settlement settlement = new Settlement(Ids.generate("stl"), merchantId,
                currency.toUpperCase(), periodStart, periodEnd,
                addBusinessDays(periodEnd, props.getSettlement().getDelayDays()));
        settlement.setGrossAmount(gross);
        settlement.setFeeAmount(fees);
        settlement.setRefundAmount(refunded);
        settlement.setDisputeAmount(chargedBack);
        // Can legitimately be negative on a bad day. That is a debt, and it is why acquirers hold
        // reserves against merchants whose refund rates are volatile.
        settlement.setNetAmount(gross - fees - refunded - chargedBack);
        settlement.setTransactionCount(count);
        settlement.setStatus(Settlement.Status.PENDING_PAYOUT);

        settlements.save(settlement);
        events.emit(merchantId, "settlement.created", snapshot(settlement));
        log.info("Settled {} for {}: {} transactions, net {}", settlement.getId(), merchantId,
                count, settlement.getNetAmount());
        return settlement;
    }

    /** Send the money. Only now does cash actually leave the gateway's own bank account. */
    @Transactional
    public Settlement payout(String merchantId, String settlementId) {
        Settlement settlement = settlements.findByIdAndMerchantId(settlementId, merchantId)
                .orElseThrow(() -> ApiException.notFound("settlement"));

        if (settlement.getStatus() != Settlement.Status.PENDING_PAYOUT) {
            throw new ApiException(400, "invalid_request_error", "settlement_unexpected_state",
                    "This settlement is " + settlement.getStatus().name().toLowerCase() + ".");
        }
        if (settlement.getNetAmount() <= 0) {
            throw new ApiException(400, "invalid_request_error", "nothing_to_pay_out",
                    "Net position is " + settlement.getNetAmount()
                            + "; it will be carried into the next cycle rather than paid out.");
        }

        settlement.setStatus(Settlement.Status.PAID);
        settlement.setPaidAt(Instant.now());
        settlements.save(settlement);

        ledger.recordPayout(merchantId, settlement.getCurrency(), settlement.getId(),
                settlement.getNetAmount());
        events.emit(merchantId, "payout.paid", snapshot(settlement));
        return settlement;
    }

    public List<Settlement> list(String merchantId) {
        return settlements.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    /**
     * Banking days, not calendar days. T+2 from a Thursday is the following Monday, and that
     * off-by-a-weekend is a support ticket nobody wants to answer twice.
     */
    private LocalDate addBusinessDays(LocalDate start, int days) {
        LocalDate date = start;
        int added = 0;
        while (added < days) {
            date = date.plusDays(1);
            var dow = date.getDayOfWeek();
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return date;
    }

    public Map<String, Object> snapshot(Settlement s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("object", "settlement");
        map.put("currency", s.getCurrency());
        map.put("gross_amount", s.getGrossAmount());
        map.put("fee_amount", s.getFeeAmount());
        map.put("refund_amount", s.getRefundAmount());
        map.put("dispute_amount", s.getDisputeAmount());
        map.put("net_amount", s.getNetAmount());
        map.put("transaction_count", s.getTransactionCount());
        map.put("status", s.getStatus().name().toLowerCase());
        map.put("period_start", s.getPeriodStart().toString());
        map.put("period_end", s.getPeriodEnd().toString());
        map.put("expected_payout_date", s.getExpectedPayoutDate().toString());
        return map;
    }
}
