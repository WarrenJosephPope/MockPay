package dev.mockpay.gateway.service;

import dev.mockpay.gateway.domain.PaymentIntent;
import dev.mockpay.gateway.domain.PaymentMethod;
import dev.mockpay.gateway.repo.PaymentIntentRepository;
import dev.mockpay.gateway.repo.PaymentMethodRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Filtering for the dashboard's payment list.
 *
 * <p>Built from composable predicates rather than a finder method per combination — status × date
 * range × amount range × last4 is sixteen finder methods before anyone asks for a seventeenth
 * filter.
 *
 * <p>The one rule that is not optional: <b>the merchant predicate is added first and unconditionally</b>.
 * Every other predicate is opt-in; that one is not, because a filter that silently returns another
 * business's payments is the worst bug this system could have.
 */
@Service
public class PaymentSearch {

    private final PaymentIntentRepository intents;
    private final PaymentMethodRepository paymentMethods;

    public PaymentSearch(PaymentIntentRepository intents, PaymentMethodRepository paymentMethods) {
        this.intents = intents;
        this.paymentMethods = paymentMethods;
    }

    /**
     * @param status     exact status, or null for any
     * @param createdFrom inclusive lower bound on creation time, or null
     * @param createdTo   exclusive upper bound, or null
     * @param amountMin   inclusive, in minor units, or null
     * @param amountMax   inclusive, or null
     * @param last4       last four digits of the card used, or null
     * @param query       free-text over description and customer reference, or null
     */
    public record Filters(PaymentIntent.Status status, Instant createdFrom, Instant createdTo,
                          Long amountMin, Long amountMax, String last4, String query) {
    }

    public Page<PaymentIntent> find(String merchantId, Filters filters, Pageable pageable) {
        List<String> paymentMethodIds = null;

        if (filters.last4() != null && !filters.last4().isBlank()) {
            // Resolved separately because PaymentIntent holds only a payment-method id and the two
            // are not mapped as a JPA relationship, so the Specification cannot join across them.
            paymentMethodIds = paymentMethods
                    .findByMerchantIdAndCardLast4(merchantId, filters.last4().trim())
                    .stream()
                    .map(PaymentMethod::getId)
                    .toList();

            if (paymentMethodIds.isEmpty()) {
                // No card on this account ends in those digits, so nothing can match. Returning
                // early avoids emitting `IN ()`, which is a syntax error in PostgreSQL.
                return Page.empty(pageable);
            }
        }

        final List<String> pmIds = paymentMethodIds;

        Specification<PaymentIntent> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            // Tenancy first, always. Everything below is a refinement of this.
            predicates.add(cb.equal(root.get("merchantId"), merchantId));

            if (filters.status() != null) {
                predicates.add(cb.equal(root.get("status"), filters.status()));
            }
            if (filters.createdFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filters.createdFrom()));
            }
            if (filters.createdTo() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), filters.createdTo()));
            }
            if (filters.amountMin() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), filters.amountMin()));
            }
            if (filters.amountMax() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), filters.amountMax()));
            }
            if (pmIds != null) {
                predicates.add(root.get("paymentMethodId").in(pmIds));
            }
            if (filters.query() != null && !filters.query().isBlank()) {
                String like = "%" + filters.query().trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("description")), like),
                        cb.like(cb.lower(root.get("customerRef")), like),
                        // Exact on id: a full payment id pasted into the search box should find it.
                        cb.equal(root.get("id"), filters.query().trim())));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return intents.findAll(spec, pageable);
    }
}
