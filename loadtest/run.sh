#!/usr/bin/env bash
# Reset the flash-sale data, run one JMeter scenario, and check the stock invariants.
#
# Usage: loadtest/run.sh <burst|sustained>
#
# Environment (defaults in brackets):
#   THREADS        JMeter threads             [burst: 500, sustained: 50]
#   STOCK          units seeded per product   [burst: 100, sustained: 1000000]
#   PRODUCT_COUNT  products seeded, ids 1..N  [1]
#   DURATION       sustained run length, s    [60]
#   RAMPUP         sustained ramp-up, s       [10]
#   HOST / PORT    application address        [localhost / 8080]
#
# WARNING: deletes every order and product in the local docker compose MySQL.
set -euo pipefail

usage() {
    echo "usage: $0 <burst|sustained>" >&2
    exit 2
}

[[ $# -eq 1 ]] || usage
SCENARIO=$1
case "$SCENARIO" in
    burst)     DEFAULT_THREADS=500; DEFAULT_STOCK=100 ;;
    # Large stock so the sustained run measures purchases, not a sold-out fast path.
    sustained) DEFAULT_THREADS=50;  DEFAULT_STOCK=1000000 ;;
    *)         usage ;;
esac

THREADS=${THREADS:-$DEFAULT_THREADS}
STOCK=${STOCK:-$DEFAULT_STOCK}
PRODUCT_COUNT=${PRODUCT_COUNT:-1}
DURATION=${DURATION:-60}
RAMPUP=${RAMPUP:-10}
HOST=${HOST:-localhost}
PORT=${PORT:-8080}

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$REPO_ROOT"

for tool in jmeter python3 docker curl; do
    command -v "$tool" >/dev/null || { echo "error: $tool is not on PATH" >&2; exit 1; }
done
for n in "$THREADS" "$STOCK" "$PRODUCT_COUNT" "$DURATION" "$RAMPUP"; do
    [[ $n =~ ^[0-9]+$ ]] || { echo "error: '$n' is not a non-negative integer" >&2; exit 2; }
done
(( PRODUCT_COUNT >= 1 )) || { echo "error: PRODUCT_COUNT must be at least 1" >&2; exit 2; }

# Read only the keys we need from .env instead of sourcing (executing) it.
env_value() {
    local value
    value=$(grep -E "^$1=" .env | tail -n 1 | cut -d= -f2-) || true
    [[ -n $value ]] || { echo "error: $1 is not set in .env" >&2; exit 1; }
    printf '%s' "$value"
}
[[ -f .env ]] || { echo "error: .env not found in $REPO_ROOT (copy .env.example)" >&2; exit 1; }
MYSQL_DATABASE=$(env_value MYSQL_DATABASE)
MYSQL_USER=$(env_value MYSQL_USER)
MYSQL_PASSWORD=$(env_value MYSQL_PASSWORD)

# Runs SQL in the compose MySQL container; MYSQL_PWD keeps the password off the command line.
sql() {
    docker compose exec -T -e MYSQL_PWD="$MYSQL_PASSWORD" mysql \
        mysql --batch --skip-column-names -u"$MYSQL_USER" "$MYSQL_DATABASE" -e "$1"
}

# a. Fail fast unless the app is up.
HEALTH_URL="http://$HOST:$PORT/actuator/health"
if ! curl -fsS --max-time 5 "$HEALTH_URL" | grep -q '"status":"UP"'; then
    echo "error: $HEALTH_URL is not UP. Start the stack with: docker compose up --build -d" >&2
    exit 1
fi

# b. Reset data: PRODUCT_COUNT products with ids 1..N, STOCK units each, version 0.
values=""
for ((id = 1; id <= PRODUCT_COUNT; id++)); do
    values+="${values:+,}($id, 'Load test product $id', $STOCK, 0)"
done
sql "DELETE FROM orders; DELETE FROM products;
     INSERT INTO products (id, product_name, available_stock, version) VALUES $values;"
INITIAL_STOCK=$(( STOCK * PRODUCT_COUNT ))
echo "Seeded $PRODUCT_COUNT product(s) x $STOCK units (total $INITIAL_STOCK)."

# c. Run JMeter into a fresh, timestamped results folder.
OUT="loadtest/results/$SCENARIO-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
echo "Running $SCENARIO: threads=$THREADS products=$PRODUCT_COUNT" \
     "$([[ $SCENARIO == sustained ]] && echo "duration=${DURATION}s rampup=${RAMPUP}s")"
jmeter -n -t "loadtest/$SCENARIO.jmx" \
    -Jthreads="$THREADS" -JproductCount="$PRODUCT_COUNT" \
    -Jduration="$DURATION" -Jrampup="$RAMPUP" \
    -Jhost="$HOST" -Jport="$PORT" \
    -Jjmeter.save.saveservice.output_format=csv \
    -Jjmeter.save.saveservice.print_field_names=true \
    -l "$OUT/results.jtl" -j "$OUT/jmeter.log" -e -o "$OUT/report"

# d. Read the final state.
FINAL_STOCK=$(sql "SELECT COALESCE(SUM(available_stock), 0) FROM products;")
ORDERS=$(sql "SELECT COUNT(*) FROM orders;")

# e. Summarise and check the invariants; the exit code is analyze.py's.
echo
status=0
python3 loadtest/analyze.py "$OUT/results.jtl" \
    --initial-stock "$INITIAL_STOCK" --final-stock "$FINAL_STOCK" --orders "$ORDERS" \
    | tee "$OUT/summary.txt" || status=$?
echo
echo "Results: $OUT  (HTML report: $OUT/report/index.html)"
exit "$status"
