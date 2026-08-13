package dev.mockpay.gateway.repo;

/**
 * Marker/holder for the repository package.
 *
 * <p>Every finder in this package takes a {@code merchantId}. That is not stylistic: an endpoint
 * that looks up a payment by id alone will happily return another merchant's payment to whoever
 * guesses the id. Making the tenant a required argument means you cannot write that query by
 * accident.
 */
public final class Repositories {
    private Repositories() {
    }
}
