#!/usr/bin/env bash
#
# Full local end-to-end test loop:
#   seed -> inject inbound -> read conversations -> read thread -> reply -> idempotent resend
#
# Requires: backend running on localhost:8080 with local profile.
# No external dependencies (Unipile stub mode).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
WEBHOOK_SECRET="${UNIPILE_WEBHOOK_SECRET:-local-dev-secret}"

echo "============================================"
echo " Local E2E Test Loop"
echo "============================================"
echo ""

# ---- Step 1: Seed ----
echo "Step 1: Seeding dev data..."
SEED=$(curl -s -X POST "${BASE_URL}/api/dev/seed")
JWT=$(echo "$SEED" | jq -r '.jwt')
ACCOUNT_ID=$(echo "$SEED" | jq -r '.accountId')
TENANT_ID=$(echo "$SEED" | jq -r '.tenantId')

if [ -z "$JWT" ] || [ "$JWT" = "null" ]; then
  echo "FAIL: seed returned no JWT. Is the backend running with -Dspring-boot.run.profiles=local?"
  exit 1
fi
echo "  tenantId:  ${TENANT_ID}"
echo "  accountId: ${ACCOUNT_ID}"
echo "  jwt:       ${JWT:0:20}..."
echo ""

# ---- Step 2: Inject inbound message ----
echo "Step 2: Injecting inbound message..."
MSG_ID="msg-$(date +%s)-$RANDOM"
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
FROM="+14155559999"
INBOUND_BODY="Hi, I need a quote for 500 units of Widget Pro"

PAYLOAD=$(cat <<ENDJSON
{
  "object": "MessageEvent",
  "event": "message_received",
  "id": "${MSG_ID}",
  "account_id": "${ACCOUNT_ID}",
  "sender_id": "${FROM}",
  "sender_name": "Alice Customer",
  "provider_name": "WHATSAPP",
  "channel_type": "whatsapp",
  "text": "${INBOUND_BODY}",
  "timestamp": "${TIMESTAMP}",
  "is_group": false,
  "attachments": []
}
ENDJSON
)

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "${BASE_URL}/api/unipile/message?secret=${WEBHOOK_SECRET}" \
  -H "Content-Type: application/json" \
  -d "${PAYLOAD}")

if [ "$HTTP_CODE" != "200" ]; then
  echo "  FAIL: webhook returned ${HTTP_CODE}"
  exit 1
fi
echo "  OK: webhook accepted (${HTTP_CODE})"

# Wait for async processing
sleep 1

# ---- Step 3: Read conversations ----
echo ""
echo "Step 3: Reading conversations..."
CONVOS=$(curl -s -H "Authorization: Bearer ${JWT}" "${BASE_URL}/api/conversations")
CONVO_COUNT=$(echo "$CONVOS" | jq '.items | length')

if [ "$CONVO_COUNT" -lt 1 ]; then
  echo "  FAIL: expected at least 1 conversation, got ${CONVO_COUNT}"
  exit 1
fi

CONVO_ID=$(echo "$CONVOS" | jq -r '.items[0].id')
CONTACT_NAME=$(echo "$CONVOS" | jq -r '.items[0].contact.displayName')
CHANNEL_TYPE=$(echo "$CONVOS" | jq -r '.items[0].channelType')
PREVIEW=$(echo "$CONVOS" | jq -r '.items[0].lastMessagePreview')

echo "  conversationId: ${CONVO_ID}"
echo "  contact:        ${CONTACT_NAME}"
echo "  channel:        ${CHANNEL_TYPE}"
echo "  preview:        ${PREVIEW}"

# ---- Step 4: Read thread ----
echo ""
echo "Step 4: Reading thread messages..."
MSGS=$(curl -s -H "Authorization: Bearer ${JWT}" "${BASE_URL}/api/conversations/${CONVO_ID}/messages")
MSG_COUNT=$(echo "$MSGS" | jq '.items | length')
FIRST_DIR=$(echo "$MSGS" | jq -r '.items[0].direction')
FIRST_BODY=$(echo "$MSGS" | jq -r '.items[0].body')

echo "  messages: ${MSG_COUNT}"
echo "  [0] ${FIRST_DIR}: ${FIRST_BODY}"

if [ "$FIRST_DIR" != "inbound" ]; then
  echo "  FAIL: expected first message to be inbound"
  exit 1
fi

# ---- Step 5: Reply ----
echo ""
echo "Step 5: Sending reply..."
IDEM_KEY="e2e-$(date +%s)-$RANDOM"
REPLY_BODY="Thanks Alice! Let me check pricing and get back to you."

REPLY=$(curl -s -H "Authorization: Bearer ${JWT}" \
  -H "Content-Type: application/json" \
  -X POST "${BASE_URL}/api/conversations/${CONVO_ID}/reply" \
  -d "{\"body\": \"${REPLY_BODY}\", \"idempotencyKey\": \"${IDEM_KEY}\"}")

REPLY_DIR=$(echo "$REPLY" | jq -r '.direction')
REPLY_AUTHOR=$(echo "$REPLY" | jq -r '.author')

echo "  direction: ${REPLY_DIR}"
echo "  author:    ${REPLY_AUTHOR}"
echo "  body:      $(echo "$REPLY" | jq -r '.body')"

if [ "$REPLY_DIR" != "outbound" ] || [ "$REPLY_AUTHOR" != "human" ]; then
  echo "  FAIL: unexpected reply shape"
  exit 1
fi

# ---- Step 6: Verify thread has both messages ----
echo ""
echo "Step 6: Verifying thread has both messages..."
MSGS2=$(curl -s -H "Authorization: Bearer ${JWT}" "${BASE_URL}/api/conversations/${CONVO_ID}/messages")
MSG_COUNT2=$(echo "$MSGS2" | jq '.items | length')
echo "  messages: ${MSG_COUNT2}"

for i in $(seq 0 $((MSG_COUNT2 - 1))); do
  DIR=$(echo "$MSGS2" | jq -r ".items[$i].direction")
  BODY=$(echo "$MSGS2" | jq -r ".items[$i].body")
  echo "  [$i] ${DIR}: ${BODY}"
done

if [ "$MSG_COUNT2" -lt 2 ]; then
  echo "  FAIL: expected 2 messages, got ${MSG_COUNT2}"
  exit 1
fi

# ---- Step 7: Idempotent resend ----
echo ""
echo "Step 7: Re-sending with same idempotencyKey (should be no-op)..."
REPLY2=$(curl -s -H "Authorization: Bearer ${JWT}" \
  -H "Content-Type: application/json" \
  -X POST "${BASE_URL}/api/conversations/${CONVO_ID}/reply" \
  -d "{\"body\": \"${REPLY_BODY}\", \"idempotencyKey\": \"${IDEM_KEY}\"}")

REPLY2_ID=$(echo "$REPLY2" | jq -r '.id')
REPLY1_ID=$(echo "$REPLY" | jq -r '.id')

echo "  first reply id:  ${REPLY1_ID}"
echo "  resend reply id: ${REPLY2_ID}"

if [ "$REPLY1_ID" != "$REPLY2_ID" ]; then
  echo "  FAIL: resend returned a different message (idempotency broken)"
  exit 1
fi

# Confirm thread still has exactly 2 messages
MSGS3=$(curl -s -H "Authorization: Bearer ${JWT}" "${BASE_URL}/api/conversations/${CONVO_ID}/messages")
MSG_COUNT3=$(echo "$MSGS3" | jq '.items | length')

if [ "$MSG_COUNT3" -ne 2 ]; then
  echo "  FAIL: expected 2 messages after resend, got ${MSG_COUNT3}"
  exit 1
fi
echo "  OK: thread still has ${MSG_COUNT3} messages (no duplicate)"

# ---- Done ----
echo ""
echo "============================================"
echo " ALL CHECKS PASSED"
echo "============================================"
echo ""
echo "Full loop verified:"
echo "  webhook -> ledger -> worker -> contact/conversation/message"
echo "  -> read API -> reply (stub send) -> idempotent resend"
