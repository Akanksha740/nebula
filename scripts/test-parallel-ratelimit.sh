#!/bin/bash
# Parallel Rate Limit Test — PRO tier (300 req/min) across 3 API keys
# Verifies rate limit is per-customer, not per-key

KEY1="nb_live_w_tzyPUFeCMpxyPaudmopj807Kanbq5dN_cYzRAoVHQ"
KEY2="nb_live_fYQqa8O3tD3ub8YQN0tYKonI6CuMSWDwOlq-JcdgqZw"
KEY3="nb_live_3CuSvdxlDUfk0jTMap7duShhvqYcgr4udAQRd5WkVuc"
BASE_URL="http://localhost:1010"
ENDPOINT="/v1/account/usage"
RESULTS_DIR=$(mktemp -d)

echo "============================================"
echo "  Parallel Rate Limit Test"
echo "  PRO tier: 300 req/min (shared across keys)"
echo "  3 keys sending ~110 requests each = 330 total"
echo "============================================"
echo ""

# Verify headers
echo "[1] Verifying PRO tier headers (Key 1):"
curl -s -D /dev/stderr -o /dev/null -H "X-API-Key: $KEY1" "$BASE_URL$ENDPOINT" 2>&1 | grep -iE "x-ratelimit|x-tier"
echo ""

# Sync to minute boundary
echo "[2] Syncing to minute boundary..."
CURRENT_SEC=$(date +%s)
NEXT_MINUTE=$(( (CURRENT_SEC / 60 + 1) * 60 ))
WAIT=$((NEXT_MINUTE - CURRENT_SEC + 1))
echo "    Waiting ${WAIT}s..."
sleep $WAIT
echo "    Start epoch: $(date +%s) (minute: $(($(date +%s) / 60)))"
echo ""

# Function to send requests with a given key
send_requests() {
    local KEY=$1
    local KEY_NAME=$2
    local COUNT=$3
    local OUTFILE=$4
    local success=0
    local limited=0

    for i in $(seq 1 $COUNT); do
        RESP=$(curl -s -o /dev/null -w "%{http_code}" -H "X-API-Key: $KEY" "$BASE_URL$ENDPOINT")
        if [ "$RESP" = "200" ]; then
            success=$((success + 1))
        elif [ "$RESP" = "429" ]; then
            limited=$((limited + 1))
        fi
    done
    echo "$KEY_NAME $success $limited" > "$OUTFILE"
}

# Launch 3 parallel workers (~110 requests each)
echo "[3] Launching 3 parallel workers (110 req each)..."
send_requests "$KEY1" "Key1" 110 "$RESULTS_DIR/key1.txt" &
PID1=$!
send_requests "$KEY2" "Key2" 110 "$RESULTS_DIR/key2.txt" &
PID2=$!
send_requests "$KEY3" "Key3" 110 "$RESULTS_DIR/key3.txt" &
PID3=$!

# Wait for all to finish
wait $PID1 $PID2 $PID3

echo "    End epoch: $(date +%s) (minute: $(($(date +%s) / 60)))"
echo ""

# Collect results
TOTAL_SUCCESS=0
TOTAL_429=0

echo "============================================"
echo "  Per-Key Results"
echo "============================================"
for f in "$RESULTS_DIR"/key*.txt; do
    read -r NAME SUCCESS LIMITED < "$f"
    TOTAL_SUCCESS=$((TOTAL_SUCCESS + SUCCESS))
    TOTAL_429=$((TOTAL_429 + LIMITED))
    printf "  %-5s → 200: %-4d  429: %-4d\n" "$NAME" "$SUCCESS" "$LIMITED"
done

echo ""
echo "============================================"
echo "  Totals"
echo "============================================"
echo "  Total requests:     330"
echo "  Successful (200):   $TOTAL_SUCCESS"
echo "  Rate limited (429): $TOTAL_429"
echo ""

if [ "$TOTAL_SUCCESS" -le 300 ] && [ "$TOTAL_429" -ge 30 ]; then
    echo "  PASS: Rate limit is shared per-customer across all keys!"
    echo "        ($TOTAL_SUCCESS <= 300 limit, $TOTAL_429 rejected)"
else
    echo "  FAIL: Expected ~300 successes and ~30 rejections"
fi
echo "============================================"

rm -rf "$RESULTS_DIR"
