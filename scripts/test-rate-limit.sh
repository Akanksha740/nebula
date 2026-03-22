#!/bin/bash
# Rate Limit Verification Script
# Tests per-minute rate limiting (Starter/Free tier = 60 req/min)

API_KEY="nb_live_fhf82GQPK_QsPAVPROfoOTWzHqOfbcfWLwe0T0B_3-s"
BASE_URL="http://localhost:1010"
ENDPOINT="/v1/account/usage"

echo "============================================"
echo "  Rate Limit Verification Test"
echo "  Tier: STARTER/FREE (60 req/min, 1000 req/day)"
echo "============================================"
echo ""

# Step 1: Show current headers
echo "[1] Current rate limit headers:"
curl -s -D /dev/stderr -o /dev/null \
    -H "X-API-Key: $API_KEY" \
    "$BASE_URL$ENDPOINT" 2>&1 | grep -iE "x-ratelimit|x-tier"
echo ""

# Step 2: Fire 65 requests rapidly to trigger per-minute limit
echo "[2] Sending 65 requests rapidly (limit = 60/min)..."
SUCCESS=0
RATE_LIMITED=0
OTHER=0
FIRST_429=""

for i in $(seq 1 65); do
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "X-API-Key: $API_KEY" \
        "$BASE_URL$ENDPOINT" 2>/dev/null)

    if [ "$RESPONSE" = "200" ]; then
        SUCCESS=$((SUCCESS + 1))
    elif [ "$RESPONSE" = "429" ]; then
        RATE_LIMITED=$((RATE_LIMITED + 1))
        if [ -z "$FIRST_429" ]; then
            FIRST_429=$i
        fi
    else
        OTHER=$((OTHER + 1))
    fi

    # Progress every 10
    if [ $((i % 10)) -eq 0 ]; then
        printf "  ...sent %d | 200: %d | 429: %d | other: %d\n" "$i" "$SUCCESS" "$RATE_LIMITED" "$OTHER"
    fi
done

echo ""
echo "============================================"
echo "  Results"
echo "============================================"
printf "  Total sent:         65\n"
printf "  Successful (200):   %d\n" "$SUCCESS"
printf "  Rate limited (429): %d\n" "$RATE_LIMITED"
printf "  Other:              %d\n" "$OTHER"
if [ -n "$FIRST_429" ]; then
    printf "  First 429 at req #: %d\n" "$FIRST_429"
fi
echo ""

if [ "$RATE_LIMITED" -gt 0 ]; then
    echo "  PASS: Per-minute rate limit is enforced!"
else
    echo "  FAIL: No 429 received in 65 requests"
fi
echo "============================================"

# Step 3: Show 429 response details
if [ "$RATE_LIMITED" -gt 0 ]; then
    echo ""
    echo "[3] Rate-limited response details:"
    curl -s -D /dev/stderr \
        -H "X-API-Key: $API_KEY" \
        "$BASE_URL$ENDPOINT" 2>&1
    echo ""
fi
