#!/usr/bin/env bash
#
# Inject a realistic Unipile-shaped inbound message webhook to the local backend.
# Matches Unipile's real webhook payload shape (developer.unipile.com).
#
# Usage:
#   ./scripts/inject-message.sh "+14155551234" "Hi, I need a quote for 500 units"
#   ./scripts/inject-message.sh                 # uses defaults
#
# Requires: backend running on localhost:8080, ACCOUNT_ID set (from seed).
# The webhook secret defaults to "local-dev-secret" (the fallback in application-local.yml).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
WEBHOOK_SECRET="${UNIPILE_WEBHOOK_SECRET:-local-dev-secret}"
ACCOUNT_ID="${ACCOUNT_ID:?Set ACCOUNT_ID to the value from /api/dev/seed}"

FROM="${1:-+14155559999}"
BODY="${2:-Hello, I would like to place an order}"
SENDER_NAME="${SENDER_NAME:-Test Customer}"

MSG_ID="msg-$(date +%s)-$RANDOM"
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Unipile's real inbound message webhook shape
# Reference: https://developer.unipile.com/docs/webhooks
PAYLOAD=$(cat <<ENDJSON
{
  "object": "MessageEvent",
  "event": "message_received",
  "id": "${MSG_ID}",
  "account_id": "${ACCOUNT_ID}",
  "sender_id": "${FROM}",
  "sender_name": "${SENDER_NAME}",
  "provider_name": "WHATSAPP",
  "channel_type": "whatsapp",
  "text": "${BODY}",
  "timestamp": "${TIMESTAMP}",
  "is_group": false,
  "attachments": []
}
ENDJSON
)

echo "--- Injecting inbound message ---"
echo "  From:    ${FROM} (${SENDER_NAME})"
echo "  Body:    ${BODY}"
echo "  MsgID:   ${MSG_ID}"
echo "  Account: ${ACCOUNT_ID}"
echo ""

HTTP_CODE=$(curl -s -o /tmp/inject-response.txt -w "%{http_code}" \
  -X POST "${BASE_URL}/api/unipile/message?secret=${WEBHOOK_SECRET}" \
  -H "Content-Type: application/json" \
  -d "${PAYLOAD}")

if [ "$HTTP_CODE" = "200" ]; then
  echo "OK (${HTTP_CODE}) - message injected"
  echo ""
  echo "Pipeline will process it asynchronously. Check:"
  echo "  curl -s -H 'Authorization: Bearer \$JWT' ${BASE_URL}/api/conversations | jq"
else
  echo "FAILED (${HTTP_CODE})"
  cat /tmp/inject-response.txt
  exit 1
fi
