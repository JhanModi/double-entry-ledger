#!/usr/bin/env bash
# A narrated tour of the API, run against a local copy of the app. Every step checks the response it expects and
# stops with an error on anything else, so a clean run shows the system behaving as documented.
# docs/demo-transcript.md is a captured run.
#
# Start the database and the app first, from the repository root:
#   docker compose up -d
#   ./mvnw -q package -DskipTests
#   java -jar target/double-entry-ledger-0.0.1-SNAPSHOT.jar      (leave it running)
# Then, in another terminal:
#   bash scripts/demo.sh
#
# Needs bash, curl 7.68 or later, jq, and Java 25. Each run creates two new API clients, so it can be run again and
# again on the same database. Their API keys are kept in a temporary file, passed to curl from that file (so they
# never appear in a command line), deleted at the end, and never printed.

set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
RUN=$(date +%Y%m%d-%H%M%S)

cd "$(dirname "$0")/.."

# --- output ---

step() { printf '\n== %s\n' "$*"; }
say() { printf '   %s\n' "$*"; }
ok() { printf '   [ok] %s\n' "$*"; }
fail() {
  printf '\n   [FAILED] %s\n' "$*" >&2
  exit 1
}

# Minor units as dollars: 100000 -> $1000.00.
usd() { printf '$%d.%02d' $(($1 / 100)) $(($1 % 100)); }

# --- set-up ---

for tool in curl jq java; do
  command -v "$tool" > /dev/null || fail "$tool is needed but isn't installed"
done
curl --help all 2> /dev/null | grep -q -- '--parallel-immediate' || fail "curl 7.68 or later is needed"

shopt -s nullglob
jars=(target/double-entry-ledger-*.jar)
JAR=${JAR:-${jars[0]:-}}
[ -n "$JAR" ] || fail "no jar in target/: run ./mvnw -q package -DskipTests first"

# On Windows (Git Bash), curl and java are native programs: give them C:/... paths rather than /tmp/... ones.
native_path() { if command -v cygpath > /dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
WORK=$(native_path "$(mktemp -d)")
trap 'rm -rf "$WORK"' EXIT

curl --silent --fail "$BASE_URL/actuator/health" > /dev/null \
  || fail "the app isn't answering at $BASE_URL: start it first (see the top of this script)"

# --- HTTP ---

# request WHO METHOD PATH [IDEMPOTENCY_KEY] [JSON_BODY]
# WHO is acme, bravo, or none (no API key). Sets STATUS and BODY; the headers are in $WORK/headers.
request() {
  local who=$1 method=$2 path=$3 idempotency_key=${4:-} body=${5:-}
  local args=(--silent --show-error --request "$method" --dump-header "$WORK/headers" --output "$WORK/body"
    --write-out '%{http_code}')
  if [ "$who" != none ]; then args+=(--header "@$WORK/auth-$who"); fi
  if [ -n "$idempotency_key" ]; then args+=(--header "Idempotency-Key: $idempotency_key"); fi
  if [ -n "$body" ]; then args+=(--header 'Content-Type: application/json' --data "$body"); fi
  STATUS=$(curl "${args[@]}" "$BASE_URL$path")
  BODY=$(cat "$WORK/body")
}

# The value of a header in the last response, or nothing.
response_header() { (grep -i "^$1:" "$WORK/headers" || true) | head -n 1 | cut -d ' ' -f 2- | tr -d '\r'; }

# A field of the last response's JSON body.
json() { jq -r "$1" <<< "$BODY" | tr -d '\r'; }

expect_status() { [ "$STATUS" = "$1" ] || fail "expected HTTP $1, got $STATUS: $BODY"; }

# expect_problem STATUS TYPE: the last response is that status, with Problem Details of that type.
expect_problem() {
  expect_status "$1"
  [ "$(json .type)" = "/problems/$2" ] || fail "expected problem type /problems/$2, got: $BODY"
}

# Sends many POST /v1/transfers at the same moment, from one curl process (--parallel). Arguments: the client, a
# directory for the responses, then one "idempotency-key|json-body" per request. Request i leaves its status, headers,
# and body in status.i, headers.i, and body.i.
parallel_transfers() {
  local who=$1 dir=$2
  shift 2
  local config="$dir/requests.curl" i=0 pair key body
  mkdir -p "$dir"
  : > "$config"
  for pair in "$@"; do
    key=${pair%%|*}
    body=${pair#*|}
    body=${body//\"/\\\"} # a quoted value in curl's config file escapes its double quotes
    if [ "$i" -gt 0 ]; then echo next >> "$config"; fi
    cat >> "$config" << EOF
url = "$BASE_URL/v1/transfers"
request = "POST"
header = "@$WORK/auth-$who"
header = "Idempotency-Key: $key"
header = "Content-Type: application/json"
data = "$body"
output = "$dir/body.$i"
dump-header = "$dir/headers.$i"
write-out = "%output{$dir/status.$i}%{http_code}"
EOF
    i=$((i + 1))
  done
  curl --silent --show-error --parallel --parallel-immediate --parallel-max 50 --config "$config"
}

# --- the API, one call at a time ---

# create_client WHO SCOPES: creates the client with the app's command line, keeps its key in $WORK/auth-WHO, and
# prints the client's id.
create_client() {
  local who=$1 scopes=$2 output key
  output=$(java -jar "$JAR" clients create --name="demo-$who-$RUN" --scopes="$scopes" 2>&1 | tr -d '\r') \
    || fail "clients create failed: $(sed -E 's/dbl_[A-Za-z0-9_-]+/dbl_[REDACTED]/g' <<< "$output" | tail -n 5)"
  key=$( (grep -E '^dbl_[0-9a-f]{16}_[A-Za-z0-9_-]{43}$' <<< "$output" || true) | head -n 1)
  [ -n "$key" ] || fail "clients create printed no API key"
  printf 'Authorization: Bearer %s\n' "$key" > "$WORK/auth-$who"
  (grep -o 'Created client [0-9a-f-]*' <<< "$output" || true) | cut -d ' ' -f 3
}

# open_account WHO: opens a USD account and prints its id.
open_account() {
  request "$1" POST /v1/accounts "" '{"currency":"USD"}'
  expect_status 201
  json .id
}

# available WHO ACCOUNT: prints the account's available balance in minor units.
available() {
  request "$1" GET "/v1/accounts/$2"
  expect_status 200
  json .balance.available.amount
}

# expect_balance WHO ACCOUNT LABEL MINOR_UNITS
expect_balance() {
  local actual
  actual=$(available "$1" "$2")
  [ "$actual" = "$4" ] || fail "expected $3 to hold $(usd "$4"), but it holds $(usd "$actual")"
  ok "$3 holds $(usd "$4")"
}

transfer_body() { # transfer_body SOURCE DESTINATION MINOR_UNITS
  printf '{"sourceAccountId":"%s","destinationAccountId":"%s","amount":{"amount":%s,"currency":"USD"}}' "$1" "$2" "$3"
}

# --- the tour ---

printf 'double-entry-ledger demo, run %s, against %s\n' "$RUN" "$BASE_URL"

step "1. Two businesses become API clients"
say "An operator creates each client and its first API key with the app's command line."
say "The key is shown once. This script keeps it in a temporary file and never prints it."
ACME=$(create_client acme read,write,admin)
ok "acme: client $ACME, scopes read, write, admin"
BRAVO=$(create_client bravo read,write)
ok "bravo: client $BRAVO, scopes read, write (no admin)"

step "2. acme opens two USD accounts"
A=$(open_account acme)
ok "account A: $A"
B=$(open_account acme)
ok "account B: $B"

step "3. acme funds account A with \$1000.00"
say "POST /v1/fundings with Idempotency-Key fund-$RUN and amount {\"amount\": 100000, \"currency\": \"USD\"}."
say "Amounts are integer minor units: 100000 is \$1000.00."
request acme POST /v1/fundings "fund-$RUN" \
  "{\"accountId\":\"$A\",\"amount\":{\"amount\":100000,\"currency\":\"USD\"},\"externalReference\":\"BANK-REF-$RUN\"}"
expect_status 201
ok "201 Created: funding $(json .id)"
say "It's one balanced ledger transaction, $(json .ledgerTransactionId):"
say "debit the platform's bank-settlement account, credit A. Request id: $(response_header X-Request-Id)"
expect_balance acme "$A" "A" 100000

step "4. acme moves \$250.00 from A to B"
TRANSFER=$(transfer_body "$A" "$B" 25000)
request acme POST /v1/transfers "transfer-$RUN" "$TRANSFER"
expect_status 201
TRANSFER_ID=$(json .id)
[ -z "$(response_header Idempotent-Replayed)" ] || fail "a first request was marked as a replay"
ok "201 Created: transfer $TRANSFER_ID"
expect_balance acme "$A" "A" 75000
expect_balance acme "$B" "B" 25000

step "5. The response is lost, so acme retries the exact same request"
request acme POST /v1/transfers "transfer-$RUN" "$TRANSFER"
expect_status 201
[ "$(response_header Idempotent-Replayed)" = true ] || fail "the retry wasn't marked Idempotent-Replayed: true"
[ "$(json .id)" = "$TRANSFER_ID" ] || fail "the retry returned a different transfer: $BODY"
ok "201 with Idempotent-Replayed: true and the same transfer id. Nothing moved again:"
expect_balance acme "$A" "A" 75000
expect_balance acme "$B" "B" 25000

step "6. acme reuses that key for a different amount"
request acme POST /v1/transfers "transfer-$RUN" "$(transfer_body "$A" "$B" 30000)"
expect_problem 422 idempotency-key-reused
ok "422 $(json .type)"
say "$(json .detail)"

step "7. acme tries to send more than A holds"
request acme POST /v1/transfers "overdraw-$RUN" "$(transfer_body "$A" "$B" 500000)"
expect_problem 422 insufficient-funds
ok "422 $(json .type): $(json .detail)"
expect_balance acme "$A" "A" 75000

step "8. An amount that isn't a whole number of cents is refused"
request acme POST /v1/transfers "decimal-$RUN" \
  "{\"sourceAccountId\":\"$A\",\"destinationAccountId\":\"$B\",\"amount\":{\"amount\":10.5,\"currency\":\"USD\"}}"
expect_problem 400 invalid-request
[ "$(json '.errors[0].field')" = amount.amount ] || fail "the 400 didn't name amount.amount: $BODY"
ok "400 $(json .type): $(json '.errors[0].field') $(json '.errors[0].message')"
say "JSON numbers like 10.5 are never rounded into money."

step "9. bravo can't see or move acme's money"
C=$(open_account bravo)
ok "bravo opens its own account C: $C"
request bravo GET "/v1/accounts/$A"
expect_problem 404 account-not-found
OWNED_BY_OTHER=$(jq -c 'del(.instance, .requestId)' <<< "$BODY")
request bravo GET /v1/accounts/00000000-0000-7000-8000-000000000000
expect_problem 404 account-not-found
MADE_UP=$(jq -c 'del(.instance, .requestId)' <<< "$BODY")
[ "$OWNED_BY_OTHER" = "$MADE_UP" ] || fail "acme's account and a made-up id got different answers"
ok "bravo reading acme's account A gets 404, the same answer as a made-up id:"
say "$OWNED_BY_OTHER"
request bravo POST /v1/transfers "steal-$RUN" "$(transfer_body "$A" "$C" 10000)"
expect_problem 404 account-not-found
ok "bravo moving money out of A gets 404: $(json .detail)"
request bravo POST /v1/fundings "fund-$RUN" \
  "{\"accountId\":\"$C\",\"amount\":{\"amount\":100000,\"currency\":\"USD\"},\"externalReference\":\"BANK-REF-$RUN\"}"
expect_problem 403 forbidden
ok "bravo funding its own account gets 403: its key has no admin scope"
request none GET "/v1/accounts/$A"
expect_problem 401 unauthorized
ok "no API key at all gets 401"
expect_balance acme "$A" "A" 75000

step "10. The same \$10.00 transfer, sent 10 times at the same moment"
say "As if a client's retry logic fired 10 times at once. One request does the work;"
say "the others wait for it at the idempotency key and get its result."
RACE_BODY=$(transfer_body "$A" "$B" 1000)
pairs=()
for _ in $(seq 1 10); do pairs+=("race-$RUN|$RACE_BODY"); done
parallel_transfers acme "$WORK/same" "${pairs[@]}"
originals=0 replays=0 other=0
ids=()
for i in $(seq 0 9); do
  status=$(cat "$WORK/same/status.$i")
  if [ "$status" = 201 ]; then
    ids+=("$(jq -r .id "$WORK/same/body.$i" | tr -d '\r')")
    if grep -qi '^Idempotent-Replayed: true' "$WORK/same/headers.$i"; then
      replays=$((replays + 1))
    else
      originals=$((originals + 1))
    fi
  else
    other=$((other + 1))
    say "request $i: HTTP $status $(jq -r .type "$WORK/same/body.$i" 2> /dev/null || true)"
  fi
done
[ "$originals" = 1 ] || fail "expected exactly 1 original response, got $originals"
[ "$(printf '%s\n' "${ids[@]}" | sort -u | wc -l | tr -d ' ')" = 1 ] || fail "the responses named different transfers"
ok "$originals original 201, $replays replays (201, Idempotent-Replayed: true), $other other; all one transfer, ${ids[0]}"
expect_balance acme "$A" "A" 74000
expect_balance acme "$B" "B" 26000

step "11. 30 different \$10.00 transfers race to empty B"
say "B holds \$260.00, so at most 26 can succeed. All 30 are sent at once, each with its own key."
pairs=()
for i in $(seq 1 30); do pairs+=("drain-$RUN-$i|$(transfer_body "$B" "$A" 1000)"); done
parallel_transfers acme "$WORK/drain" "${pairs[@]}"
succeeded=0 refused=0 busy=0
for i in $(seq 0 29); do
  case "$(cat "$WORK/drain/status.$i")" in
    201) succeeded=$((succeeded + 1)) ;;
    422) refused=$((refused + 1)) ;;
    503) busy=$((busy + 1)) ;;
    *) fail "request $i: unexpected HTTP $(cat "$WORK/drain/status.$i"): $(cat "$WORK/drain/body.$i")" ;;
  esac
done
ok "$succeeded succeeded (201), $refused refused (422 insufficient-funds), $busy busy (503)"
# A 503 means "try again later": nothing moved. Without any, exactly 26 fit.
[ "$succeeded" -le 26 ] || fail "more transfers succeeded than B could pay for"
[ "$busy" -gt 0 ] || [ "$succeeded" = 26 ] || fail "expected exactly 26 to succeed, got $succeeded"
expect_balance acme "$B" "B" $((26000 - 1000 * succeeded))
expect_balance acme "$A" "A" $((74000 + 1000 * succeeded))

step "12. Every cent is accounted for"
A_NOW=$(available acme "$A")
B_NOW=$(available acme "$B")
[ $((A_NOW + B_NOW)) = 100000 ] || fail "A + B is $(usd $((A_NOW + B_NOW))), not \$1000.00"
ok "A + B = $(usd "$A_NOW") + $(usd "$B_NOW") = $(usd $((A_NOW + B_NOW))): the \$1000.00 funded, no more, no less"
request acme GET "/v1/accounts/$A/entries?limit=100"
expect_status 200
[ "$(json .nextCursor)" = null ] || fail "A's history is longer than one page"
say "A's history, summarized:"
while read -r type direction count total; do
  say "  $count $type $direction entries, totalling $(usd "$total")"
done < <(jq -r '.entries | group_by(.type + " " + .direction)[]
  | "\(.[0].type) \(.[0].direction) \(length) \([.[].amount.amount] | add)"' <<< "$BODY" | tr -d '\r')
FROM_ENTRIES=$(json '[.entries[] | if .direction == "CREDIT" then .amount.amount else -.amount.amount end] | add')
[ "$FROM_ENTRIES" = "$A_NOW" ] || fail "A's entries add up to $(usd "$FROM_ENTRIES"), but its balance is $(usd "$A_NOW")"
ok "credits minus debits = $(usd "$FROM_ENTRIES"), exactly A's balance: the balance is a projection of the entries"

printf '\nDemo complete: every check passed.\n'
