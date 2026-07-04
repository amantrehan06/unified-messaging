# Local testing guide

Two modes: **dummy injection** (instant, no external deps) and **real WhatsApp** (occasional confirmation via ngrok).
95% of testing is the dummy loop.

## Mode 1: Dummy injection (primary dev loop)

Unipile send calls hit a stub that logs and returns a fake ID.
The backend runs against real Neon; everything else is real.

### Start the backend

```bash
cd backend
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

With no `UNIPILE_DSN` in `.env`, the stub client activates automatically.
You'll see in the logs: `Unipile client: STUB (no unipile.dsn configured)`.

The webhook secret defaults to `local-dev-secret` when `UNIPILE_WEBHOOK_SECRET` is unset.

### Run the full loop test

```bash
./scripts/test-full-loop.sh
```

This seeds a tenant/user/channel, injects a message, reads the API, replies, and verifies idempotent resend.
All checks should pass.

### Manual injection

```bash
# 1. Seed dev data (returns JWT + accountId)
SEED=$(curl -s -X POST http://localhost:8080/api/dev/seed)
export JWT=$(echo $SEED | jq -r '.jwt')
export ACCOUNT_ID=$(echo $SEED | jq -r '.accountId')

# 2. Inject an inbound message
ACCOUNT_ID=$ACCOUNT_ID ./scripts/inject-message.sh "+14155551234" "Hi, need a quote"

# 3. Check conversations
curl -s -H "Authorization: Bearer $JWT" http://localhost:8080/api/conversations | jq

# 4. Read thread (replace CONVO_ID)
curl -s -H "Authorization: Bearer $JWT" http://localhost:8080/api/conversations/CONVO_ID/messages | jq

# 5. Reply
curl -s -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/conversations/CONVO_ID/reply \
  -d '{"body": "Thanks! Let me check.", "idempotencyKey": "my-key-1"}' | jq

# 6. Resend same key (should return same message, no duplicate)
curl -s -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/conversations/CONVO_ID/reply \
  -d '{"body": "Thanks! Let me check.", "idempotencyKey": "my-key-1"}' | jq
```

### What to watch in logs

- `Ledger row created: eventId=...` - webhook accepted
- `Processed eventId=..., conversationId=...` - worker ran
- `[STUB] sendMessage: accountId=..., to=..., body='...'` - reply sent via stub

## Mode 2: Real WhatsApp via ngrok (occasional confirmation)

For testing with a real connected WhatsApp account.
Switch from stub to live by setting `UNIPILE_DSN` in `backend/.env`.

### Prerequisites

- Real Unipile credentials (DSN, API key, webhook secret)
- ngrok installed (`brew install ngrok`)
- A WhatsApp number to test with

### Steps

**1. Add Unipile credentials to `backend/.env`:**

```bash
UNIPILE_DSN=https://api1.unipile.com:13111
UNIPILE_API_KEY=your-real-api-key
UNIPILE_WEBHOOK_SECRET=your-real-webhook-secret
```

With `UNIPILE_DSN` set, the backend uses the real `HttpUnipileClient`.
You'll see in the logs: `Unipile client: LIVE (dsn=https://api1.unipile.com:13111)`.

**2. Start the backend:**

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

**3. Start ngrok:**

```bash
ngrok http 8080
```

Note the `https://xxxx.ngrok-free.app` URL.

**4. Update backend base URL:**

Set `APP_BACKEND_BASE_URL` in `.env` to the ngrok URL so Unipile's hosted auth
and webhook callbacks reach your local machine:

```bash
APP_BACKEND_BASE_URL=https://xxxx.ngrok-free.app
```

Restart the backend after this change.

**5. Connect WhatsApp:**

Open `http://localhost:5173` in a browser, log in with Google, go to Channels,
and connect WhatsApp.
The hosted auth flow will redirect through Unipile and back to your local frontend.

**6. Test the loop:**

- Text the connected WhatsApp number from your phone.
- Watch the backend logs for `Ledger row created` and `Processed eventId`.
- Check the inbox at `http://localhost:5173` - the message should appear.
- Reply from the inbox - it should arrive on your phone.

### Switching back to stub mode

Remove or clear `UNIPILE_DSN` in `backend/.env` and restart the backend.
The stub client activates automatically.

## Environment variables reference

| Variable | Stub mode | Real mode | Where |
|---|---|---|---|
| `UNIPILE_DSN` | unset/empty | `https://api1.unipile.com:13111` | `backend/.env` |
| `UNIPILE_API_KEY` | unset/empty | real key | `backend/.env` |
| `UNIPILE_WEBHOOK_SECRET` | defaults to `local-dev-secret` | real secret | `backend/.env` |
| `APP_BACKEND_BASE_URL` | `http://localhost:8080` (default) | ngrok URL | `backend/.env` |

No code changes needed to switch modes - it's all config.
