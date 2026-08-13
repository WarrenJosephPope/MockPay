/**
 * MockPay browser SDK.
 *
 * The job of a payments client library is narrower than it looks. It exists so that card details
 * go from the customer's keyboard to the gateway without ever passing through the merchant's own
 * servers — which is what keeps the merchant out of PCI scope — and so that every integrator does
 * not have to reimplement the awkward parts of the flow: waiting on a 3-D Secure challenge,
 * surviving a customer who closes the popup, and polling for an outcome that arrives out of band.
 *
 * Only the publishable key lives here. It cannot move money.
 *
 *   const mockpay = MockPay('pk_test_demo_us_publishable');
 *   const { paymentMethod } = await mockpay.createPaymentMethod({ type: 'card', card: {...} });
 *   const { paymentIntent, error } = await mockpay.confirmPayment({
 *     clientSecret, paymentMethod: paymentMethod.id
 *   });
 */
(function (global) {
  'use strict';

  function MockPay(publishableKey, options) {
    if (!(this instanceof MockPay)) {
      return new MockPay(publishableKey, options);
    }
    if (!publishableKey || publishableKey.indexOf('pk_') !== 0) {
      throw new Error('MockPay needs a publishable key (pk_...). Never put a secret key in a browser.');
    }
    options = options || {};
    this.publishableKey = publishableKey;
    this.baseUrl = (options.baseUrl || inferBaseUrl()).replace(/\/$/, '');
  }

  function inferBaseUrl() {
    // Works when the SDK is served by the gateway itself; pass baseUrl explicitly otherwise.
    var script = document.currentScript;
    if (script && script.src) {
      var url = new URL(script.src);
      return url.origin;
    }
    return window.location.origin;
  }

  async function request(url, options) {
    var response = await fetch(url, options);
    var body;
    try {
      body = await response.json();
    } catch (e) {
      body = { error: { code: 'network_error', message: 'The gateway returned an unreadable response.' } };
    }
    if (!response.ok) {
      // Normalised into { error } so callers never have to check response.ok themselves.
      return { error: body.error || { code: 'unknown_error', message: 'Request failed.' } };
    }
    return { data: body };
  }

  MockPay.prototype = {

    /**
     * Exchange raw instrument details for a token.
     *
     * This is the call that matters for compliance: the card number goes straight from the browser
     * to the gateway. The merchant's backend sees only `pm_...`, which is useless to an attacker
     * and carries no obligations.
     */
    createPaymentMethod: async function (params) {
      var url = this.baseUrl + '/v1/public/payment_methods?key=' + encodeURIComponent(this.publishableKey);
      var result = await request(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params)
      });
      if (result.error) {
        return { error: result.error };
      }
      return { paymentMethod: result.data };
    },

    /** Read the current state of an intent, authenticating with its client secret. */
    retrievePaymentIntent: async function (clientSecret) {
      var id = clientSecret.split('_secret_')[0];
      var url = this.baseUrl + '/v1/public/payment_intents/' + id
        + '?client_secret=' + encodeURIComponent(clientSecret);
      var result = await request(url, { method: 'GET' });
      if (result.error) {
        return { error: result.error };
      }
      return { paymentIntent: result.data };
    },

    /**
     * Confirm a payment, and carry it all the way to a terminal state.
     *
     * The important behaviour is what happens when the gateway answers `requires_action`. The
     * payment is not finished, and it is not failed — the customer has to do something in someone
     * else's interface. This method opens that interface and then waits, because the alternative is
     * every integrator writing their own popup-and-poll loop and most of them getting the
     * cancellation case wrong.
     */
    confirmPayment: async function (params) {
      var clientSecret = params.clientSecret;
      var id = clientSecret.split('_secret_')[0];

      var url = this.baseUrl + '/v1/public/payment_intents/' + id
        + '/confirm?client_secret=' + encodeURIComponent(clientSecret);

      var result = await request(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          payment_method: params.paymentMethod,
          return_url: params.returnUrl || window.location.href
        })
      });

      if (result.error) {
        return { error: result.error };
      }

      var intent = result.data;

      if (intent.status === 'requires_action' && intent.next_action) {
        if (params.onAction) {
          params.onAction(intent.next_action);
        }
        return await this._handleNextAction(intent, clientSecret);
      }

      return this._settle(intent);
    },

    /**
     * Drive the customer through a challenge and wait for the result.
     *
     * A popup rather than a full-page redirect, so the merchant's page — and any state it is
     * holding — survives. Real SDKs prefer an iframe where the issuer permits it, and fall back to
     * a redirect on mobile browsers that block popups.
     */
    _handleNextAction: async function (intent, clientSecret) {
      var actionUrl = intent.next_action.url;
      var popup = window.open(actionUrl, 'mockpay_challenge', 'width=460,height=640');

      if (!popup) {
        return {
          error: {
            code: 'popup_blocked',
            message: 'The authentication window was blocked. Allow popups, or redirect to '
              + actionUrl + ' instead.'
          }
        };
      }

      var self = this;
      return new Promise(function (resolve) {
        var elapsed = 0;
        var intervalMs = 1200;
        // Long enough for someone to find their phone and read an SMS, short enough that an
        // abandoned checkout does not hang forever.
        var timeoutMs = 5 * 60 * 1000;

        var timer = setInterval(async function () {
          elapsed += intervalMs;

          var polled = await self.retrievePaymentIntent(clientSecret);
          if (polled.error) {
            clearInterval(timer);
            closeQuietly(popup);
            resolve({ error: polled.error });
            return;
          }

          var current = polled.paymentIntent;

          // The outcome is read from the gateway, never inferred from the popup closing. A
          // customer who pays and then closes the window has still paid.
          if (current.status !== 'requires_action') {
            clearInterval(timer);
            closeQuietly(popup);
            resolve(self._settle(current));
            return;
          }

          if (popup.closed) {
            // Give the server a beat to record a result that was in flight as the window closed.
            var recheck = await self.retrievePaymentIntent(clientSecret);
            if (!recheck.error && recheck.paymentIntent.status !== 'requires_action') {
              clearInterval(timer);
              resolve(self._settle(recheck.paymentIntent));
              return;
            }
            clearInterval(timer);
            resolve({
              error: {
                code: 'authentication_abandoned',
                message: 'The customer closed the authentication window before finishing.'
              },
              paymentIntent: current
            });
            return;
          }

          if (elapsed >= timeoutMs) {
            clearInterval(timer);
            closeQuietly(popup);
            resolve({
              error: {
                code: 'authentication_timeout',
                message: 'Authentication was not completed in time.'
              },
              paymentIntent: current
            });
          }
        }, intervalMs);
      });
    },

    /** Map a terminal intent into the { paymentIntent } / { error } shape callers expect. */
    _settle: function (intent) {
      if (intent.status === 'succeeded' || intent.status === 'requires_capture'
        || intent.status === 'processing') {
        return { paymentIntent: intent };
      }
      return {
        paymentIntent: intent,
        error: intent.last_payment_error || {
          code: 'payment_' + intent.status,
          message: 'The payment ended in state: ' + intent.status
        }
      };
    }
  };

  function closeQuietly(popup) {
    try {
      popup.close();
    } catch (e) {
      /* the popup may already be gone; nothing to do */
    }
  }

  global.MockPay = MockPay;
})(window);
