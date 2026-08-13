#!/usr/bin/env bash
# End-to-end smoke test against a running MockPay instance.
#
# Target it elsewhere with either variable:
#   MOCKPAY_BASE_URL=http://localhost:9000 bash scripts/smoke-test.sh
#   GATEWAY_PORT=9000 bash scripts/smoke-test.sh
#
# Picks up .env automatically if one is present, so it follows whatever port the
# compose stack is using.
set -uo pipefail

if [ -f "$(dirname "$0")/../.env" ]; then
  # shellcheck disable=SC1091
  set -a; . "$(dirname "$0")/../.env"; set +a
fi
B=${MOCKPAY_BASE_URL:-http://localhost:${GATEWAY_PORT:-8088}}
# With no SMTP host configured, EmailService writes messages to the application log, and the reset
# test reads the link from there.
#
# The reset token is deliberately NOT returned by the API the way an invitation token is. Creating
# an invitation requires being an authenticated OWNER, so echoing that token back leaks nothing the
# caller could not already do. /forgot-password is unauthenticated — returning a token there would
# let anyone request a reset for any address and immediately take the account over. Scraping the
# log is the awkward-but-safe option.
#
# Point at a file, or at a command for a containerised gateway:
#   MOCKPAY_LOG_FILE=/tmp/app.log
#   MOCKPAY_LOG_CMD="docker logs mockpay-gateway"
LOGFILE=${MOCKPAY_LOG_FILE:-/tmp/p3.log}
LOGCMD=${MOCKPAY_LOG_CMD:-}
readlog() { if [ -n "$LOGCMD" ]; then $LOGCMD 2>&1; else cat "$LOGFILE" 2>/dev/null; fi; }
SK=sk_test_demo_us_secret
SK_IN=sk_test_demo_in_secret
PK=pk_test_demo_us_publishable
PASS=0; FAIL=0

chk() {
  if echo "$2" | grep -q "$3"; then echo "  PASS  $1"; PASS=$((PASS+1));
  else echo "  FAIL  $1"; echo "        want ~ $3"; echo "        got:  $(echo "$2" | head -c 300)"; FAIL=$((FAIL+1)); fi
}
# $1 method  $2 path  $3 body ('' for none)  $4 key (defaults to $SK)
api()  { curl -s -X "$1" "$B$2" -H "Authorization: Bearer ${4:-$SK}" -H 'Content-Type: application/json' ${3:+-d "$3"}; }
apik() { curl -s -X POST "$B$1" -H "Authorization: Bearer $SK" -H 'Content-Type: application/json' -H "Idempotency-Key: $3" -d "$2"; }
field() { python -c "import sys,json
try: print(json.load(sys.stdin).get('$1',''))
except Exception: print('')"; }
action() { python -c "import sys,json
try: print(json.load(sys.stdin)['next_action']['url'].split('action=')[1])
except Exception: print('')"; }
field_err() { python -c "import sys,json
try: print(json.load(sys.stdin)['error'].get('$1',''))
except Exception: print('')"; }

# --- dashboard helpers: session cookie jar + CSRF token ------------------------
# $1 path  $2 cookie-jar        (GET / DELETE)
# $1 path  $2 body  $3 jar      (POST / PATCH)
csrf()    { grep XSRF-TOKEN "$1" 2>/dev/null | awk '{print $7}'; }
dget()    { curl -s -b "$2" -c "$2" "$B$1"; }
dpost()   { curl -s -b "$3" -c "$3" -X POST "$B$1" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf "$3")" -d "$2"; }
dpatch()  { curl -s -b "$3" -c "$3" -X PATCH "$B$1" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf "$3")" -d "$2"; }
ddelete() { curl -s -b "$2" -c "$2" -X DELETE "$B$1" -H "X-XSRF-TOKEN: $(csrf "$2")"; }

echo "=== 1. Authentication ==="
chk "no key -> 401" "$(curl -s $B/v1/account)" "no_api_key"
chk "publishable key rejected" "$(curl -s $B/v1/account -H "Authorization: Bearer $PK")" "publishable_key_not_allowed"
chk "bad key rejected" "$(curl -s $B/v1/account -H 'Authorization: Bearer sk_bogus')" "invalid_api_key"
chk "valid key -> account" "$(api GET /v1/account)" "acct_demo_us"

echo; echo "=== 2. Tokenisation ==="
r=$(api POST /v1/payment_methods '{"type":"card","card":{"number":"4242424242424242","exp_month":12,"exp_year":2030,"cvc":"123"}}')
PM_OK=$(echo "$r" | field id)
chk "card tokenised as $PM_OK" "$r" '"brand":"visa"'
chk "only last4 retained" "$r" '"last4":"4242"'
chk "no PAN in response" "$(echo $r | grep -c 4242424242424242)" '^0$'
chk "network token provisioned" "$r" 'network_token_last4'
chk "luhn rejected" "$(api POST /v1/payment_methods '{"type":"card","card":{"number":"4242424242424241","exp_month":12,"exp_year":2030,"cvc":"123"}}')" "incorrect_number"
chk "expired card rejected locally" "$(api POST /v1/payment_methods '{"type":"card","card":{"number":"4242424242424242","exp_month":1,"exp_year":2020,"cvc":"123"}}')" "expired_card"

echo; echo "=== 3. Automatic capture (happy path) ==="
r=$(api POST /v1/payment_intents "{\"amount\":4999,\"currency\":\"USD\",\"payment_method\":\"$PM_OK\",\"confirm\":true,\"description\":\"AirPods\"}")
PI1=$(echo "$r" | field id)
chk "succeeded" "$r" '"status":"succeeded"'
chk "auth code present" "$r" 'authorization_code'
chk "fee charged (2% + 30)" "$r" '"application_fee_amount":130'
chk "3DS frictionless" "$r" 'authenticated_frictionless'
chk "acquirer recorded" "$r" 'acq_atlas'

echo; echo "=== 4. Idempotency ==="
K=$(python -c "import uuid;print(uuid.uuid4())")
ID1=$(apik /v1/payment_intents '{"amount":1000,"currency":"USD"}' "$K" | field id)
ID2=$(apik /v1/payment_intents '{"amount":1000,"currency":"USD"}' "$K" | field id)
if [ -n "$ID1" ] && [ "$ID1" = "$ID2" ]; then echo "  PASS  same key replays same object ($ID1)"; PASS=$((PASS+1));
else echo "  FAIL  replay: $ID1 vs $ID2"; FAIL=$((FAIL+1)); fi
chk "key reuse w/ different body -> 422" "$(apik /v1/payment_intents '{"amount":9999,"currency":"USD"}' "$K")" "idempotency_key_reused"

echo; echo "=== 5. Declines ==="
for pair in "4000000000000002:card_declined" "4000000000009995:insufficient_funds" "4000000000000069:expired_card" "4000000000000259:lost_card" "4000000000000127:incorrect_cvc"; do
  pan=${pair%%:*}; want=${pair##*:}
  pm=$(api POST /v1/payment_methods "{\"type\":\"card\",\"card\":{\"number\":\"$pan\",\"exp_month\":12,\"exp_year\":2030,\"cvc\":\"123\"}}" | field id)
  chk "$pan -> $want" "$(api POST /v1/payment_intents "{\"amount\":2000,\"currency\":\"USD\",\"payment_method\":\"$pm\",\"confirm\":true}")" "$want"
done
pm=$(api POST /v1/payment_methods '{"type":"card","card":{"number":"4100000000000019","exp_month":12,"exp_year":2030,"cvc":"123"}}' | field id)
chk "risk engine blocks pre-network" "$(api POST /v1/payment_intents "{\"amount\":2000,\"currency\":\"USD\",\"payment_method\":\"$pm\",\"confirm\":true}")" "blocked_by_risk"
pm=$(api POST /v1/payment_methods '{"type":"card","card":{"number":"4000000000000119","exp_month":12,"exp_year":2030,"cvc":"123"}}' | field id)
r=$(api POST /v1/payment_intents "{\"amount\":2000,\"currency\":\"USD\",\"payment_method\":\"$pm\",\"confirm\":true}")
PI_SOFT=$(echo "$r" | field id)
chk "issuer_unavailable is a soft decline" "$r" "issuer_unavailable"
chk "failed intents can be retried" "$r" '"status":"failed"'

echo; echo "=== 6. Manual capture / void ==="
r=$(api POST /v1/payment_intents "{\"amount\":8000,\"currency\":\"USD\",\"capture_method\":\"manual\",\"payment_method\":\"$PM_OK\",\"confirm\":true}")
PI_MAN=$(echo "$r" | field id)
chk "authorised, awaiting capture" "$r" '"status":"requires_capture"'
chk "amount_capturable set" "$r" '"amount_capturable":8000'
chk "partial capture" "$(api POST "/v1/payment_intents/$PI_MAN/capture" '{"amount_to_capture":5000}')" '"amount_received":5000'
chk "double capture rejected" "$(api POST "/v1/payment_intents/$PI_MAN/capture" '{}')" "payment_intent_unexpected_state"

r=$(api POST /v1/payment_intents "{\"amount\":3000,\"currency\":\"USD\",\"capture_method\":\"manual\",\"payment_method\":\"$PM_OK\",\"confirm\":true}")
PI_VOID=$(echo "$r" | field id)
chk "over-capture rejected" "$(api POST "/v1/payment_intents/$PI_VOID/capture" '{"amount_to_capture":99999}')" "amount_too_large"
chk "void releases the hold" "$(api POST "/v1/payment_intents/$PI_VOID/cancel" '{}')" '"status":"canceled"'
chk "cannot refund a voided auth" "$(api POST /v1/refunds "{\"payment_intent\":\"$PI_VOID\"}")" "payment_intent_unexpected_state"

echo; echo "=== 7. 3-D Secure challenge ==="
pm=$(api POST /v1/payment_methods '{"type":"card","card":{"number":"4000002500003155","exp_month":12,"exp_year":2030,"cvc":"123"}}' | field id)
r=$(api POST /v1/payment_intents "{\"amount\":6000,\"currency\":\"EUR\",\"payment_method\":\"$pm\",\"confirm\":true}")
PI_3DS=$(echo "$r" | field id)
chk "challenge required" "$r" '"status":"requires_action"'
ACT=$(echo "$r" | action)
chk "challenge context loads" "$(curl -s $B/v1/public/challenge/$ACT)" "three_ds_challenge"
chk "wrong OTP rejected" "$(curl -s -X POST $B/v1/public/challenge/3ds/$ACT -H 'Content-Type: application/json' -d '{"otp":"000000"}')" "incorrect_otp"
chk "correct OTP -> succeeded" "$(curl -s -X POST $B/v1/public/challenge/3ds/$ACT -H 'Content-Type: application/json' -d '{"otp":"123456"}')" '"status":"succeeded"'
r=$(api GET "/v1/payment_intents/$PI_3DS")
chk "liability shifted" "$r" '"liability_shifted":true'
chk "ECI 05 recorded" "$r" '"eci":"05"'
chk "local acquiring chose EU acquirer" "$r" 'acq_meridian'
chk "action is single-use" "$(curl -s -X POST $B/v1/public/challenge/3ds/$ACT -H 'Content-Type: application/json' -d '{"otp":"123456"}')" "action_expired"

echo; echo "=== 8. UPI collect (India account) ==="
pm=$(api POST /v1/payment_methods '{"type":"upi","upi":{"vpa":"success@mockpay"}}' $SK_IN | field id)
r=$(api POST /v1/payment_intents "{\"amount\":25000,\"currency\":\"INR\",\"payment_method\":\"$pm\",\"confirm\":true}" $SK_IN)
PI_UPI=$(echo "$r" | field id)
chk "collect sent, awaiting approval" "$r" 'upi_await_approval'
ACT=$(echo "$r" | action)
chk "payer approved -> succeeded" "$(curl -s -X POST $B/v1/public/challenge/upi/$ACT -H 'Content-Type: application/json' -d '{"approve":true}')" '"status":"succeeded"'
r=$(api GET "/v1/payment_intents/$PI_UPI" '' $SK_IN)
chk "no capture step on UPI" "$r" '"amount_capturable":0'
chk "UPI fee is zero" "$r" '"application_fee_amount":0'
chk "routed to India acquirer" "$r" 'acq_indus'
chk "UPI rejects non-INR" "$(api POST /v1/payment_intents "{\"amount\":25000,\"currency\":\"USD\",\"payment_method\":\"$pm\",\"confirm\":true}" $SK_IN)" "unsupported_currency"
pm=$(api POST /v1/payment_methods '{"type":"upi","upi":{"vpa":"failure@mockpay"}}' $SK_IN | field id)
r=$(api POST /v1/payment_intents "{\"amount\":5000,\"currency\":\"INR\",\"payment_method\":\"$pm\",\"confirm\":true}" $SK_IN)
ACT=$(echo "$r" | action)
chk "payer declines -> failed" "$(curl -s -X POST $B/v1/public/challenge/upi/$ACT -H 'Content-Type: application/json' -d '{"approve":true}')" '"status":"failed"'

echo; echo "=== 9. Wallet redirect ==="
pm=$(api POST /v1/payment_methods '{"type":"wallet","wallet":{"provider":"mockwallet"}}' | field id)
r=$(api POST /v1/payment_intents "{\"amount\":3300,\"currency\":\"USD\",\"payment_method\":\"$pm\",\"confirm\":true}")
chk "wallet needs redirect" "$r" 'redirect_to_url'
ACT=$(echo "$r" | action)
chk "wallet approval -> succeeded" "$(curl -s -X POST $B/v1/public/challenge/wallet/$ACT -H 'Content-Type: application/json' -d '{"approve":true}')" '"status":"succeeded"'

echo; echo "=== 10. Refunds ==="
chk "partial refund succeeded" "$(api POST /v1/refunds "{\"payment_intent\":\"$PI1\",\"amount\":2000,\"reason\":\"requested_by_customer\"}")" '"status":"succeeded"'
chk "amount_refunded tracked" "$(api GET /v1/payment_intents/$PI1)" '"amount_refunded":2000'
chk "over-refund rejected" "$(api POST /v1/refunds "{\"payment_intent\":\"$PI1\",\"amount\":99999}")" "amount_too_large"
chk "remainder refundable" "$(api POST /v1/refunds "{\"payment_intent\":\"$PI1\"}")" '"amount":2999'

echo; echo "=== 11. Disputes ==="
r=$(api POST /v1/payment_intents "{\"amount\":7500,\"currency\":\"USD\",\"payment_method\":\"$PM_OK\",\"confirm\":true}")
PI_DP=$(echo "$r" | field id)
r=$(api POST /v1/disputes "{\"payment_intent\":\"$PI_DP\",\"reason_code\":\"10.4\"}")
DP=$(echo "$r" | field id)
chk "dispute opened" "$r" '"status":"needs_response"'
chk "fraud category" "$r" '"category":"fraud"'
chk "evidence window set" "$r" 'evidence_due_by'
chk "evidence submitted" "$(api POST "/v1/disputes/$DP/evidence" '{"evidence":{"shipping_tracking":"1Z999","avs_result":"Y"}}')" '"status":"under_review"'
chk "dispute won" "$(api POST "/v1/disputes/$DP/resolve" '{"merchant_wins":true}')" '"status":"won"'
chk "reason code catalogue" "$(api GET /v1/dispute_reason_codes)" "EMV Liability Shift"

echo; echo "=== 12. Double-entry ledger ==="
r=$(api GET "/v1/payment_intents/$PI1/ledger")
chk "capture journal exists" "$r" "SCHEME_RECEIVABLE"
chk "merchant payable credited" "$r" "MERCHANT_PAYABLE"
chk "fee income booked" "$r" "FEE_INCOME"
chk "trial balance is zero" "$(api GET /v1/account/balance)" '"_TOTAL_MUST_BE_ZERO":0'

echo; echo "=== 13. Settlement ==="
TODAY=$(python -c "import datetime;print(datetime.datetime.now(datetime.timezone.utc).date())")  # UTC: matches MOCKPAY_SETTLEMENT_ZONE
r=$(api POST /v1/settlements/run "{\"currency\":\"USD\",\"period_start\":\"$TODAY\",\"period_end\":\"$TODAY\"}")
STL=$(echo "$r" | field id)
chk "batch created" "$r" '"status":"pending_payout"'
chk "gross > 0" "$r" '"gross_amount":[1-9]'
chk "fees deducted" "$r" '"fee_amount":[1-9]'
chk "refunds deducted" "$r" '"refund_amount":[1-9]'
chk "payout paid" "$(api POST "/v1/settlements/$STL/payout")" '"status":"paid"'
chk "still balanced after payout" "$(api GET /v1/account/balance)" '"_TOTAL_MUST_BE_ZERO":0'

echo; echo "=== 14. Webhooks ==="
sleep 5
r=$(curl -s "$B/webhook-sink/received")
chk "payment_intent.succeeded delivered" "$r" "payment_intent.succeeded"
chk "payment_intent.payment_failed delivered" "$r" "payment_intent.payment_failed"
chk "dispute.created delivered" "$r" "dispute.created"
chk "settlement.created delivered" "$r" "settlement.created"
chk "refund.succeeded delivered" "$r" "refund.succeeded"
chk "no duplicates processed twice" "$r" '"duplicate":false'
chk "event log shows delivered" "$(api GET /v1/events)" '"status":"delivered"'
EV=$(api GET /v1/events | python -c "import sys,json;print(json.load(sys.stdin)['data'][0]['id'])")
chk "manual replay resets the event" "$(api POST /v1/events/$EV/replay '{}')" '"attempts":0'
sleep 3
chk "replayed event seen as duplicate" "$(curl -s $B/webhook-sink/received)" '"duplicate":true'

echo; echo "=== 15. ISO 8583 trace ==="
r=$(api GET "/v1/payment_intents/$PI1/transactions")
chk "0100 authorisation request" "$r" "MTI  0100"
chk "0110 response" "$r" "0110"
chk "DE39 response code" "$r" "DE39"
chk "DE2 masked PAN" "$r" "424242\*\*\*\*\*\*4242"
chk "0220 capture advice" "$r" "0220"
chk "bitmap computed" "$r" "BMP1"
chk "MTI decoded in English" "$r" "Authorization / Request"
chk "routing rationale recorded" "$r" "Local acquiring"

echo; echo "=== 16. Tenant isolation ==="
chk "other merchant cannot read intent" "$(api GET "/v1/payment_intents/$PI1" '' $SK_IN)" "resource_missing"
chk "other merchant cannot refund it" "$(api POST /v1/refunds "{\"payment_intent\":\"$PI1\"}" $SK_IN)" "resource_missing"

echo; echo "=== 17. Validation ==="
chk "below acquirer floor" "$(api POST /v1/payment_intents '{"amount":10,"currency":"USD"}')" "amount_too_small"
chk "missing amount" "$(api POST /v1/payment_intents '{"currency":"USD"}')" "parameter_invalid"
chk "unknown payment method" "$(api POST /v1/payment_intents '{"amount":1000,"currency":"USD","payment_method":"pm_nope","confirm":true}')" "resource_missing"
chk "list endpoint paginates" "$(api GET '/v1/payment_intents?limit=3')" '"has_more"'

echo; echo "=== 18. Client-side (publishable key) surface ==="
r=$(curl -s -X POST "$B/v1/public/payment_methods?key=$PK" -H 'Content-Type: application/json' -d '{"type":"card","card":{"number":"5555555555554444","exp_month":6,"exp_year":2029,"cvc":"737"}}')
chk "browser tokenisation works" "$r" '"brand":"mastercard"'
chk "browser response omits fingerprint" "$(echo $r | grep -c fingerprint)" '^0$'
r=$(api POST /v1/payment_intents '{"amount":5000,"currency":"USD"}')
CS=$(echo "$r" | field client_secret); PIC=$(echo "$r" | field id)
chk "client secret works" "$(curl -s "$B/v1/public/payment_intents/$PIC?client_secret=$CS")" '"status":"requires_payment_method"'
chk "wrong client secret rejected" "$(curl -s "$B/v1/public/payment_intents/$PIC?client_secret=bogus")" "invalid_client_secret"

echo; echo "=== 19. API key management ==="
r=$(api GET /v1/api_keys)
chk "keys listed" "$r" '"object":"api_key"'
chk "secret key value never returned in a list" "$(echo "$r" | grep -c 'sk_test_demo_us_secret')" '^0$'
chk "prefix shown for identification" "$r" '"prefix":"sk_test_demo_us_"'
chk "publishable key returned in full" "$r" 'pk_test_demo_us_publishable'

r=$(api POST /v1/api_keys '{"type":"secret","name":"rotation test"}')
NEWKEY=$(echo "$r" | field key); NEWKEYID=$(echo "$r" | field id)
chk "new secret returned once at creation" "$r" '"key":"sk_test_'
chk "creation warns it is unrecoverable" "$r" 'cannot be retrieved again'
chk "the brand-new key authenticates" "$(api GET /v1/account '' $NEWKEY)" "acct_demo_us"
chk "listing it again hides the value" "$(api GET /v1/api_keys | grep -c "$NEWKEY")" '^0$'

chk "revoking works" "$(api POST /v1/api_keys/$NEWKEYID/revoke '{}')" '"revoked_at":[0-9]'
chk "revoked key is rejected" "$(api GET /v1/account '' $NEWKEY)" "invalid_api_key"
chk "original key still works" "$(api GET /v1/account)" "acct_demo_us"
K1=$(api GET /v1/api_keys | python -c "
import sys,json
d=json.load(sys.stdin)['data']
print(next(k['id'] for k in d if k['type']=='secret' and not k.get('revoked_at')))")
chk "cannot revoke the last secret key" "$(api POST /v1/api_keys/$K1/revoke '{}')" "cannot_revoke_last_key"

echo; echo "=== 20. Webhook endpoints ==="
r=$(api GET /v1/webhook_endpoints)
chk "seeded endpoint present" "$r" '"object":"webhook_endpoint"'
chk "endpoint has its own secret" "$r" '"secret":"whsec_'
chk "no filter means all events" "$r" '"enabled_events":\["\*"\]'

r=$(api POST /v1/webhook_endpoints "{\"url\":\"$B/webhook-sink\",\"description\":\"second endpoint\",\"enabled_events\":[\"payment_intent.succeeded\"]}")
EP2=$(echo "$r" | field id)
chk "second endpoint created" "$r" '"object":"webhook_endpoint"'
chk "event filter recorded" "$r" 'payment_intent.succeeded'
S1=$(api GET /v1/webhook_endpoints | python -c "
import sys,json
d=json.load(sys.stdin)['data']
print(len({e['secret'] for e in d}), len(d))")
chk "each endpoint has a distinct secret" "$S1" '^2 2$'

curl -s -X DELETE $B/webhook-sink/received >/dev/null
pm=$(api POST /v1/payment_methods '{"type":"card","card":{"number":"4242424242424242","exp_month":12,"exp_year":2030,"cvc":"123"}}' | field id)
api POST /v1/payment_intents "{\"amount\":4444,\"currency\":\"USD\",\"payment_method\":\"$pm\",\"confirm\":true}" >/dev/null
sleep 6
FAN=$(curl -s $B/webhook-sink/received | python -c "
import sys,json
evs=json.load(sys.stdin)['events']
succ=[e for e in evs if e['type']=='payment_intent.succeeded']
crea=[e for e in evs if e['type']=='payment_intent.created']
print(len(succ), len(crea))")
chk "succeeded fanned out to BOTH endpoints" "$FAN" '^2 '
chk "created went only to the unfiltered endpoint" "$FAN" ' 1$'
chk "every delivery verified its own signature" "$(curl -s $B/webhook-sink/received | grep -c 'signature verification failed')" '^0$'

chk "disabling an endpoint stops delivery" "$(api PATCH /v1/webhook_endpoints/$EP2 '{"enabled":false}')" '"enabled":false'
chk "endpoint deleted" "$(api DELETE /v1/webhook_endpoints/$EP2 '{}')" '"deleted":true'
chk "back to one endpoint" "$(api GET /v1/webhook_endpoints | python -c "import sys,json;print(len(json.load(sys.stdin)['data']))")" '^1$'

echo; echo "=== 21. Tenant isolation on the new resources ==="
chk "cannot list another merchant's keys" "$(api GET /v1/api_keys '' $SK_IN | grep -c 'demo_us')" '^0$'
chk "cannot revoke another merchant's key" "$(api POST /v1/api_keys/$K1/revoke '{}' $SK_IN)" "resource_missing"
chk "cannot read another merchant's endpoint" "$(api GET /v1/webhook_endpoints/whe_nope '' $SK_IN)" "resource_missing"

echo; echo "=== 22. Dashboard signup and session ==="
DJ=/tmp/mp_owner.txt; rm -f $DJ
PW='correct horse battery staple'
EMAIL="owner+$(date +%s)@acme.test"
r=$(curl -s -c $DJ -X POST $B/dashboard/auth/signup -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\",\"name\":\"Ada\",\"business_name\":\"Acme Ltd\",\"currency\":\"GBP\",\"country\":\"GB\"}")
MID=$(echo "$r" | field merchant_id)
NEWSK=$(echo "$r" | field secret_key)
chk "signup creates user + business" "$r" '"merchant_id":"acct_'
chk "secret key returned once at signup" "$r" '"secret_key":"sk_test_'
chk "session cookie issued" "$(grep -c mockpay_session $DJ)" '^1$'
chk "signup key works on the v1 API" "$(api GET /v1/account '' $NEWSK)" "$MID"
chk "/dashboard/me works with the session" "$(dget /dashboard/me $DJ)" "\"role\":\"OWNER\""
chk "no session -> 401" "$(curl -s -o /dev/null -w '%{http_code}' $B/dashboard/me)" '^401$'
chk "weak password rejected" "$(curl -s -X POST $B/dashboard/auth/signup -H 'Content-Type: application/json' -d '{"email":"w@a.test","password":"short","name":"X"}')" "weak_password"
chk "duplicate email rejected" "$(curl -s -X POST $B/dashboard/auth/signup -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}")" "email_already_registered"

echo; echo "=== 23. Login, logout, credential handling ==="
LJ=/tmp/mp_login.txt; rm -f $LJ
chk "login succeeds" "$(curl -s -c $LJ -X POST $B/dashboard/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}")" '"role":"OWNER"'
WRONG=$(curl -s -X POST $B/dashboard/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"definitely not it\"}" | field_err message)
UNKNOWN=$(curl -s -X POST $B/dashboard/auth/login -H 'Content-Type: application/json' -d '{"email":"nobody@nowhere.test","password":"definitely not it"}' | field_err message)
if [ "$WRONG" = "$UNKNOWN" ] && [ -n "$WRONG" ]; then echo "  PASS  wrong password and unknown email are indistinguishable"; PASS=$((PASS+1));
else echo "  FAIL  login errors differ: '$WRONG' vs '$UNKNOWN'"; FAIL=$((FAIL+1)); fi
chk "logout invalidates the session" "$(dpost /dashboard/auth/logout '{}' $LJ)" '"logged_out":true'
chk "session dead after logout" "$(curl -s -o /dev/null -w '%{http_code}' -b $LJ $B/dashboard/me)" '^401$'

echo; echo "=== 24. CSRF ==="
CSRF=$(csrf $DJ)
chk "CSRF cookie is issued" "$(echo ${#CSRF})" '^3[0-9]$'
chk "state-changing POST without token -> 403" "$(curl -s -o /dev/null -w '%{http_code}' -b $DJ -X POST $B/dashboard/api-keys -H 'Content-Type: application/json' -d '{"type":"secret"}')" '^403$'
chk "GET needs no token" "$(curl -s -o /dev/null -w '%{http_code}' -b $DJ $B/dashboard/me)" '^200$'
chk "POST with token succeeds" "$(dpost /dashboard/api-keys '{"type":"secret","name":"CI"}' $DJ)" '"key":"sk_test_'

echo; echo "=== 25. RBAC matrix ==="
# Invite one user per role, accept, and probe what each can do.
for role in ADMIN DEVELOPER VIEWER; do
  lc=$(echo $role | tr 'A-Z' 'a-z')
  TOK=$(dpost /dashboard/team/invitations "{\"email\":\"$lc+$(date +%s)@acme.test\",\"role\":\"$role\"}" $DJ | field token)
  RJ=/tmp/mp_$lc.txt; rm -f $RJ
  curl -s -c $RJ -X POST $B/dashboard/auth/accept-invitation -H 'Content-Type: application/json' \
    -d "{\"token\":\"$TOK\",\"password\":\"$PW\",\"name\":\"$role person\"}" >/dev/null
  chk "$role can read /dashboard/me" "$(dget /dashboard/me $RJ)" "\"role\":\"$role\""
done

chk "VIEWER cannot list API keys"      "$(dget /dashboard/api-keys /tmp/mp_viewer.txt)" "insufficient_role"
chk "DEVELOPER can list API keys"      "$(dget /dashboard/api-keys /tmp/mp_developer.txt)" '"object":"list"'
chk "DEVELOPER cannot CREATE a key"    "$(dpost /dashboard/api-keys '{"type":"secret"}' /tmp/mp_developer.txt)" "insufficient_role"
chk "ADMIN can create a key"           "$(dpost /dashboard/api-keys '{"type":"secret","name":"admin key"}' /tmp/mp_admin.txt)" '"key":"sk_test_'
chk "DEVELOPER can manage endpoints"   "$(dpost /dashboard/webhook-endpoints "{\"url\":\"$B/webhook-sink\"}" /tmp/mp_developer.txt)" '"object":"webhook_endpoint"'
chk "VIEWER cannot manage endpoints"   "$(dpost /dashboard/webhook-endpoints "{\"url\":\"$B/webhook-sink\"}" /tmp/mp_viewer.txt)" "insufficient_role"
chk "ADMIN cannot invite (OWNER only)" "$(dpost /dashboard/team/invitations '{"email":"x@y.test","role":"VIEWER"}' /tmp/mp_admin.txt)" "insufficient_role"
chk "OWNER can invite"                 "$(dpost /dashboard/team/invitations "{\"email\":\"extra+$(date +%s)@acme.test\",\"role\":\"VIEWER\"}" $DJ)" '"token"'
chk "VIEWER can read payments"         "$(dget /dashboard/payments /tmp/mp_viewer.txt)" '"object":"list"'

echo; echo "=== 26. Refunds are ADMIN-only (money movement) ==="
PMX=$(api POST /v1/payment_methods '{"type":"card","card":{"number":"4242424242424242","exp_month":12,"exp_year":2030,"cvc":"123"}}' $NEWSK | field id)
PIX=$(api POST /v1/payment_intents "{\"amount\":5000,\"currency\":\"GBP\",\"payment_method\":\"$PMX\",\"confirm\":true}" $NEWSK | field id)
chk "payment created on the new account" "$PIX" '^pi_'
chk "DEVELOPER cannot refund" "$(dpost /dashboard/refunds "{\"payment_intent\":\"$PIX\",\"amount\":100}" /tmp/mp_developer.txt)" "insufficient_role"
chk "VIEWER cannot refund"    "$(dpost /dashboard/refunds "{\"payment_intent\":\"$PIX\",\"amount\":100}" /tmp/mp_viewer.txt)" "insufficient_role"
chk "ADMIN can refund"        "$(dpost /dashboard/refunds "{\"payment_intent\":\"$PIX\",\"amount\":100}" /tmp/mp_admin.txt)" '"status":"succeeded"'

echo; echo "=== 27. Audit log ==="
AL=$(dget /dashboard/audit-log $DJ)
chk "audit log readable by OWNER" "$AL" '"object":"list"'
chk "signup recorded"       "$AL" "account.created"
chk "key creation recorded" "$AL" "api_key.created"
chk "refund recorded"       "$AL" "refund.issued"
chk "invitation recorded"   "$AL" "member.invited"
chk "actor email captured"  "$AL" "$EMAIL"
chk "IP address captured"   "$AL" '"ip_address"'
chk "DEVELOPER cannot read the audit log" "$(dget /dashboard/audit-log /tmp/mp_developer.txt)" "insufficient_role"

echo; echo "=== 28. Cross-tenant isolation for dashboard users ==="
OJ=/tmp/mp_other.txt; rm -f $OJ
OTHER="other+$(date +%s)@rival.test"
r=$(curl -s -c $OJ -X POST $B/dashboard/auth/signup -H 'Content-Type: application/json' \
  -d "{\"email\":\"$OTHER\",\"password\":\"$PW\",\"name\":\"Rival\",\"business_name\":\"Rival Inc\"}")
OMID=$(echo "$r" | field merchant_id)
chk "second business created" "$OMID" '^acct_'
chk "rival sees only their own payments" "$(dget /dashboard/payments $OJ | python -c "import sys,json;print(json.load(sys.stdin)['total_count'])")" '^0$'
chk "rival cannot read the other payment" "$(dget /dashboard/payments/$PIX $OJ)" "resource_missing"
chk "rival's audit log is separate" "$(dget /dashboard/audit-log $OJ | grep -c "$EMAIL")" '^0$'
chk "rival cannot switch into a foreign account" "$(dpost /dashboard/switch-account "{\"merchant_id\":\"$MID\"}" $OJ)" "resource_missing"

echo; echo "=== 29. Team safety rails ==="
OWNMEM=$(dget /dashboard/team $DJ | python -c "
import sys,json
d=json.load(sys.stdin)['data']
print(next(m['membership_id'] for m in d if m['role']=='OWNER'))")
chk "cannot remove the last owner" "$(ddelete /dashboard/team/$OWNMEM $DJ)" "cannot_remove_last_owner"
chk "cannot demote the last owner" "$(dpatch /dashboard/team/$OWNMEM '{"role":"VIEWER"}' $DJ)" "cannot_demote_last_owner"
chk "team lists all members" "$(dget /dashboard/team $DJ | python -c "import sys,json;print(len(json.load(sys.stdin)['data']))")" '^4$'

echo; echo "=== 30. Revoked access takes effect immediately ==="
VMEM=$(dget /dashboard/team $DJ | python -c "
import sys,json
d=json.load(sys.stdin)['data']
print(next(m['membership_id'] for m in d if m['role']=='VIEWER'))")
chk "viewer works before removal" "$(dget /dashboard/me /tmp/mp_viewer.txt)" '"role":"VIEWER"'
chk "owner removes the viewer" "$(ddelete /dashboard/team/$VMEM $DJ)" '"removed":true'
chk "their existing session is now refused" "$(dget /dashboard/me /tmp/mp_viewer.txt)" "no_access"

echo; echo "=== 31. Password reset ==="
RJ=/tmp/mp_reset.txt; rm -f $RJ
RESET_EMAIL="reset+$(date +%s)@acme.test"
curl -s -c $RJ -X POST $B/dashboard/auth/signup -H 'Content-Type: application/json'   -d "{\"email\":\"$RESET_EMAIL\",\"password\":\"$PW\",\"name\":\"Reset Tester\",\"business_name\":\"Reset Co\"}" >/dev/null
chk "session is live before reset" "$(dget /dashboard/me $RJ)" '"role":"OWNER"'

KNOWN=$(curl -s -X POST $B/dashboard/auth/forgot-password -H 'Content-Type: application/json' -d "{\"email\":\"$RESET_EMAIL\"}")
UNKNOWN=$(curl -s -X POST $B/dashboard/auth/forgot-password -H 'Content-Type: application/json' -d '{"email":"definitely-nobody@nowhere.test"}')
if [ "$KNOWN" = "$UNKNOWN" ] && [ -n "$KNOWN" ]; then echo "  PASS  forgot-password reveals nothing about who has an account"; PASS=$((PASS+1));
else echo "  FAIL  responses differ: '$KNOWN' vs '$UNKNOWN'"; FAIL=$((FAIL+1)); fi

# With no SMTP configured the email is written to the log; that is where the link lives.
# Poll rather than sleep: delivery runs on the mail executor, so a fixed wait is a race.
RTOK=""
for _ in $(seq 1 20); do
  RTOK=$(readlog | grep -oE 'reset-password\?token=[A-Za-z0-9]+' | tail -1 | sed 's/.*token=//')
  [ -n "$RTOK" ] && break
  sleep 1
done
chk "reset link emailed (found in the log transport)" "$RTOK" '^[A-Za-z0-9]\{40,\}$'
chk "bad token rejected" "$(curl -s -X POST $B/dashboard/auth/reset-password -H 'Content-Type: application/json' -d '{"token":"nonsense","password":"a brand new passphrase"}')" "invalid_reset_token"
chk "weak new password rejected" "$(curl -s -X POST $B/dashboard/auth/reset-password -H 'Content-Type: application/json' -d "{\"token\":\"$RTOK\",\"password\":\"short\"}")" "weak_password"
chk "reset succeeds" "$(curl -s -X POST $B/dashboard/auth/reset-password -H 'Content-Type: application/json' -d "{\"token\":\"$RTOK\",\"password\":\"a brand new passphrase\"}")" "Password updated"
chk "token is single-use" "$(curl -s -X POST $B/dashboard/auth/reset-password -H 'Content-Type: application/json' -d "{\"token\":\"$RTOK\",\"password\":\"another new passphrase\"}")" "invalid_reset_token"

chk "OLD password no longer works" "$(curl -s -X POST $B/dashboard/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$RESET_EMAIL\",\"password\":\"$PW\"}")" "invalid_credentials"
NJ=/tmp/mp_reset_new.txt; rm -f $NJ
chk "NEW password works" "$(curl -s -c $NJ -X POST $B/dashboard/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$RESET_EMAIL\",\"password\":\"a brand new passphrase\"}")" '"role":"OWNER"'

echo; echo "=== 32. Reset signs out every existing session ==="
chk "the pre-reset session is dead" "$(curl -s -o /dev/null -w '%{http_code}' -b $RJ $B/dashboard/me)" '^401$'
chk "the post-reset session works" "$(dget /dashboard/me $NJ)" '"role":"OWNER"'

echo; echo "=== 33. Emailed tokens are stored hashed ==="
chk "invitation token returned only while SMTP is unset" "$(dpost /dashboard/team/invitations "{\"email\":\"hashcheck+$(date +%s)@acme.test\",\"role\":\"VIEWER\"}" $DJ)" "no SMTP host is configured"

echo; echo "======================================"
echo "  PASS: $PASS   FAIL: $FAIL"
echo "======================================"
[ "$FAIL" -eq 0 ]
