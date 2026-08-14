/**
 * MockPay browser SDK.
 *
 * Two ways to take a payment, and the difference between them is mostly about PCI scope.
 *
 * -- 1. Hosted checkout (recommended) ---------------------------------------
 *
 *   mockpay.open({
 *     clientSecret,                       // from your server
 *     onSuccess: (r) => confirmOrder(r.paymentIntentId),
 *     onFailure: (e) => showMessage(e.message),
 *     onClose:   ()  => {},
 *   });
 *
 * Opens MockPay's own payment page in an iframe over your site. The card fields belong to the
 * gateway's document, so same-origin policy means your JavaScript cannot read them even by
 * accident -- and neither can anything injected into your page. Your checkout sits in PCI SAQ A
 * territory rather than SAQ A-EP, and you write no payment form at all.
 *
 * -- 2. Your own form -------------------------------------------------------
 *
 *   const { paymentMethod } = await mockpay.createPaymentMethod({ type: 'card', card: {...} });
 *   const { paymentIntent, error } = await mockpay.confirmPayment({
 *     clientSecret, paymentMethod: paymentMethod.id
 *   });
 *
 * Total control of the UI, and the card number passes through your DOM on its way to the gateway.
 * It still never reaches your server, but your page is now part of the cardholder data environment,
 * so a compromised script on that page can skim it. That is the Magecart attack, and it is why
 * mode 1 exists.
 *
 * Only the publishable key lives here. It cannot move money.
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
     * Open the hosted payment page in an iframe over the merchant's site.
     *
     * The callbacks are UI signals, not proof of anything -- see _receive below.
     *
     * @param params.clientSecret  from the PaymentIntent your server created
     * @param params.onSuccess     ({ paymentIntentId, status })
     * @param params.onFailure     ({ code, message, paymentIntentId, status })
     * @param params.onClose       the customer dismissed the sheet
     */
    open: function (params) {
      if (!params || !params.clientSecret) {
        throw new Error('mockpay.open needs a clientSecret from your server.');
      }
      if (this._overlay) {
        // Two sheets would mean two live sessions and two sets of listeners.
        return;
      }

      var self = this;
      var url = this.baseUrl + '/checkout/hosted'
        + '?client_secret=' + encodeURIComponent(params.clientSecret)
        + '&key=' + encodeURIComponent(this.publishableKey);

      var overlay = document.createElement('div');
      overlay.setAttribute('data-mockpay', 'overlay');
      overlay.style.cssText = [
        'position:fixed', 'inset:0', 'z-index:2147483647',
        'background:rgba(9,11,15,.62)', 'display:flex',
        'align-items:center', 'justify-content:center', 'padding:16px',
        'opacity:0', 'transition:opacity .18s ease'
      ].join(';');

      var frame = document.createElement('iframe');
      frame.src = url;
      frame.setAttribute('title', 'Secure payment');
      frame.style.cssText = [
        'width:100%', 'max-width:420px', 'height:min(640px,92vh)',
        'border:0', 'border-radius:14px', 'background:#fff',
        'box-shadow:0 24px 64px rgba(0,0,0,.4)'
      ].join(';');

      overlay.appendChild(frame);
      document.body.appendChild(overlay);

      // Stop the page behind from scrolling under the sheet.
      var previousOverflow = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
      requestAnimationFrame(function () { overlay.style.opacity = '1'; });

      this._overlay = overlay;
      this._params = params;
      this._previousOverflow = previousOverflow;

      // Clicking the backdrop dismisses, the way every payment sheet does. Clicks inside the frame
      // never reach here -- they belong to the other document.
      overlay.addEventListener('click', function (event) {
        if (event.target === overlay) self._close('close');
      });

      this._onMessage = function (event) { self._receive(event); };
      window.addEventListener('message', this._onMessage);
    },

    /**
     * Handle a message from the hosted page.
     *
     * Two checks before anything is believed, and both matter:
     *
     *   1. event.origin must be the gateway. Without this, ANY page -- an ad iframe, another tab
     *      holding a handle on this window -- could post "payment succeeded", and the merchant
     *      would fulfil an order nobody paid for.
     *   2. The shape must be ours, so unrelated postMessage traffic from analytics, embeds and
     *      browser extensions is ignored rather than misread.
     *
     * Even then the message is only a UI signal. The merchant's SERVER must confirm the payment --
     * from the webhook, or by fetching the intent with its secret key -- before shipping anything.
     * An attacker controls their own browser completely; they do not control your webhook.
     */
    _receive: function (event) {
      if (event.origin !== this.baseUrl) return;
      var data = event.data;
      if (!data || data.source !== 'mockpay') return;

      var params = this._params || {};

      switch (data.type) {
        case 'complete':
          this._close();
          if (params.onSuccess) {
            params.onSuccess({ paymentIntentId: data.paymentIntentId, status: data.status });
          }
          break;
        case 'failed':
          // Deliberately NOT closed: the customer is reading the decline message and may want to
          // try another card. The sheet closes when they dismiss it.
          if (params.onFailure) {
            params.onFailure({
              paymentIntentId: data.paymentIntentId,
              status: data.status,
              code: data.code,
              message: data.message
            });
          }
          break;
        case 'cancel':
          this._close('close');
          break;
        default:
          // 'ready', 'challenge', and anything added later, are informational.
          if (params.onEvent) params.onEvent(data);
      }
    },

    _close: function (reason) {
      if (!this._overlay) return;
      window.removeEventListener('message', this._onMessage);
      this._overlay.remove();
      document.body.style.overflow = this._previousOverflow || '';
      var params = this._params;
      this._overlay = null;
      this._params = null;
      this._onMessage = null;
      if (reason === 'close' && params && params.onClose) params.onClose();
    },

    /** Dismiss the sheet from the merchant's own code, e.g. on a route change. */
    close: function () {
      this._close('close');
    },

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
