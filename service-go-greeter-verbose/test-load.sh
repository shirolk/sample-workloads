#!/usr/bin/env bash
# Usage: ./test-load.sh <base-url> [duration-seconds]
# Example: ./test-load.sh https://my-service.example.com 120

set -euo pipefail

BASE="${1:?Usage: $0 <base-url> [duration-seconds]}"
BASE="${BASE%/}"
DURATION="${2:-60}"

# Calls that exercise every log level:
#   DEBUG  – every request in/out
#   INFO   – normal greets, health, random
#   WARN   – missing name, slow random (1-in-10), long name
#   ERROR  – unsupported language
CALLS=(
  "/greeter/greet"
  "/greeter/greet?name=Alice"
  "/greeter/greet?name=Bob&lang=es"
  "/greeter/greet?name=Charlie&lang=fr"
  "/greeter/greet?name=Diana&lang=de"
  "/greeter/greet?name=Eve&lang=jp"
  "/greeter/greet?lang=klingon"
  "/greeter/greet?name=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
  "/greeter/random"
  "/greeter/random"
  "/greeter/random"
  "/health"
)

end=$((SECONDS + DURATION))
count=0

echo "Target : ${BASE}"
echo "Running: ${DURATION}s  (Ctrl+C to stop early)"
echo "────────────────────────────────────────────"

while [ $SECONDS -lt $end ]; do
  call="${CALLS[$((RANDOM % ${#CALLS[@]}))]}"
  url="${BASE}${call}"

  status=$(curl -s -o /dev/null -w "%{http_code}" "$url")
  count=$((count + 1))
  printf "[%4d] HTTP %s  %s\n" "$count" "$status" "$url"

  sleep $((RANDOM % 3 + 1))
done

echo "────────────────────────────────────────────"
echo "Done — ${count} requests in ${DURATION}s"
