#!/usr/bin/env bash
set -e

export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

pass() { echo -e "${GREEN}PASS${NC} $1"; }
fail() { echo -e "${RED}FAIL${NC} $1"; }

cleanup() {
  echo ""
  echo "Shutting down..."
  [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" 2>/dev/null
  [ -n "$FRONTEND_PID" ] && kill "$FRONTEND_PID" 2>/dev/null
  wait 2>/dev/null
}
trap cleanup EXIT

ROOT="$(cd "$(dirname "$0")" && pwd)"

# 1. Backend build
echo "=== Building backend ==="
cd "$ROOT/backend"
mvn -B compile -q 2>&1 && pass "Backend compiles" || { fail "Backend compile"; exit 1; }

# 2. Frontend build
echo "=== Building frontend ==="
cd "$ROOT/frontend"
npm run build --silent 2>&1 && pass "Frontend builds (type-check + bundle)" || { fail "Frontend build"; exit 1; }

# 3. Start backend
echo "=== Starting backend ==="
cd "$ROOT/backend"
mvn -q spring-boot:run -Dspring-boot.run.profiles=local > /tmp/um-backend.log 2>&1 &
BACKEND_PID=$!

echo "Waiting for backend..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8080/api/health > /dev/null 2>&1; then
    break
  fi
  sleep 1
done

# 4. Check health endpoint
HEALTH=$(curl -sf http://localhost:8080/api/health 2>/dev/null)
if echo "$HEALTH" | grep -q '"status":"ok"'; then
  pass "GET /api/health -> $HEALTH"
else
  fail "GET /api/health (is backend running?)"
  echo "Backend log tail:"
  tail -20 /tmp/um-backend.log
  exit 1
fi

# 5. Check actuator
ACTUATOR=$(curl -sf http://localhost:8080/actuator/health 2>/dev/null)
if echo "$ACTUATOR" | grep -q 'UP\|status'; then
  pass "GET /actuator/health -> $ACTUATOR"
else
  fail "GET /actuator/health"
fi

# 6. Start frontend dev server
echo "=== Starting frontend ==="
cd "$ROOT/frontend"
VITE_API_BASE_URL=http://localhost:8080 npx vite --port 5173 > /tmp/um-frontend.log 2>&1 &
FRONTEND_PID=$!

for i in $(seq 1 10); do
  if curl -sf http://localhost:5173 > /dev/null 2>&1; then
    break
  fi
  sleep 1
done

if curl -sf http://localhost:5173 > /dev/null 2>&1; then
  pass "Frontend dev server at http://localhost:5173"
else
  fail "Frontend dev server"
fi

echo ""
echo "=== All checks done ==="
echo "Backend:  http://localhost:8080/api/health"
echo "Frontend: http://localhost:5173"
echo ""
echo "Press Ctrl+C to stop both servers."
wait
