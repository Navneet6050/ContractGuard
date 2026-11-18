#!/usr/bin/env bash
# Contract Guard CI gate — runs the publishing gate as a dry-run and fails the build
# when the candidate spec would damage a registered consumer.
#
# Usage:
#   scripts/ci-check.sh --base-url http://localhost:8080 --service quote-api --spec specs/quote-api/3-price-lines.yaml [--strict] [--actor ci-bot]
#
# Exit codes (the contract, documented in README.md):
#   0  decision PUBLISH (auto-publishable), or PENDING_ACK without --strict
#      (breaking but zero AFFECTED consumers — the real publish still requires a
#      human acknowledgement, so CI may pass it with a warning)
#   1  decision BLOCKED — breaking change with at least one AFFECTED consumer
#      (fail the build; publish requires an override with written justification)
#   2  HTTP 422 — the candidate spec itself is invalid (malformed YAML, broken $ref…)
#   3  operational failure — server unreachable, non-JSON body, unexpected HTTP status
#   4  usage error (missing arguments, unreadable spec file)
# With --strict, PENDING_ACK also exits 1.
set -u

BASE_URL="http://localhost:8080"
SERVICE=""
SPEC_FILE=""
ACTOR="${CI_ACTING_USER:-ci-bot}"
STRICT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --base-url) BASE_URL="$2"; shift 2 ;;
    --service)  SERVICE="$2"; shift 2 ;;
    --spec)     SPEC_FILE="$2"; shift 2 ;;
    --actor)    ACTOR="$2"; shift 2 ;;
    --strict)   STRICT=1; shift ;;
    *) echo "ci-check: unknown argument '$1'" >&2; exit 4 ;;
  esac
done

if [ -z "$SERVICE" ] || [ -z "$SPEC_FILE" ]; then
  echo "ci-check: --service and --spec are required" >&2
  echo "usage: ci-check.sh --base-url URL --service SLUG --spec FILE [--strict] [--actor NAME]" >&2
  exit 4
fi
if [ ! -r "$SPEC_FILE" ]; then
  echo "ci-check: cannot read spec file '$SPEC_FILE'" >&2
  exit 4
fi

command -v curl >/dev/null 2>&1 || { echo "ci-check: curl is required" >&2; exit 4; }
# interpreter discovery: python3 first, then python (Windows often lacks the python3 name)
PYTHON=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'print(1)' >/dev/null 2>&1; then
    PYTHON="$candidate"; break
  fi
done
[ -n "$PYTHON" ] || { echo "ci-check: python3 (or python) is required" >&2; exit 4; }

FILENAME="$(basename "$SPEC_FILE")"
REQUEST_FILE="$(mktemp)"
RESPONSE_FILE="$(mktemp)"
trap 'rm -f "$REQUEST_FILE" "$RESPONSE_FILE"' EXIT

# the API takes a JSON envelope: {"yaml": "<spec text>", "filename": "<name>"}
"$PYTHON" - "$SPEC_FILE" "$FILENAME" "$REQUEST_FILE" <<'PYEOF' || exit 4
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    spec = fh.read()
with open(sys.argv[3], "w", encoding="utf-8") as out:
    json.dump({"yaml": spec, "filename": sys.argv[2]}, out)
PYEOF

HTTP_STATUS="$(curl -sS -o "$RESPONSE_FILE" -w '%{http_code}' \
  -X POST "$BASE_URL/api/services/$SERVICE/check" \
  -H "Content-Type: application/json" \
  -H "X-Acting-User: $ACTOR" \
  --data-binary @"$REQUEST_FILE" 2>"$RESPONSE_FILE.curlerr" )" \
  || { echo "ci-check: cannot reach Contract Guard at $BASE_URL ($(cat "$RESPONSE_FILE.curlerr"))" >&2; rm -f "$RESPONSE_FILE.curlerr"; exit 3; }
rm -f "$RESPONSE_FILE.curlerr"

"$PYTHON" - "$HTTP_STATUS" "$STRICT" "$SERVICE" "$SPEC_FILE" "$RESPONSE_FILE" <<'PYEOF'
import functools, json, sys

print = functools.partial(print, flush=True)  # keep stdout and stderr in order

status, strict, service, spec_file, path = sys.argv[1], sys.argv[2] == "True", sys.argv[3], sys.argv[4], sys.argv[5]
try:
    with open(path, encoding="utf-8") as fh:
        body = fh.read()
except OSError as exc:
    print(f"ci-check: unreadable response body ({exc})", file=sys.stderr)
    sys.exit(3)

if status == "422":
    try:
        report = json.loads(body)
        errors = []
        if isinstance(report, dict):
            if isinstance(report.get("errors"), list):
                errors = report["errors"]
            elif isinstance(report.get("error"), dict) and isinstance(report["error"].get("details"), list):
                errors = report["error"]["details"]
        parts = []
        for e in errors or ["invalid spec"]:
            if isinstance(e, dict):
                parts.append(f"{e.get('pointer', '')}: {e.get('message', e)}")
            else:
                parts.append(str(e))
        errors = "; ".join(parts) or "invalid spec"
    except ValueError:
        errors = "invalid spec"
    print(f"ci-check: INVALID SPEC ({spec_file}): {errors}")
    sys.exit(2)

if status != "200":
    print(f"ci-check: unexpected HTTP {status} from /check: {body[:400]}", file=sys.stderr)
    sys.exit(3)

try:
    report = json.loads(body)
except ValueError as exc:
    print(f"ci-check: non-JSON response body ({exc})", file=sys.stderr)
    sys.exit(3)

decision = report.get("decision", "?")
summary = report.get("summary", {}) or {}
affected = summary.get("affectedCount", "?")
required = report.get("requiredAction", "")
findings = report.get("findings", []) or []
consumers = summary.get("consumers", []) or []
breaking_count = sum(1 for f in findings if str(f.get("severity", "")).lower() == "breaking")

print(f"ci-check: {service} <- {spec_file}")
print(f"  decision: {decision}   breaking findings: {breaking_count}   affected consumers: {affected}")
for finding in findings:
    if str(finding.get("severity", "")).lower() in ("breaking", "warn"):
        print(f"  [{finding.get('severity')}] {finding.get('code')} @ {finding.get('location', '')}"
              + (f" field={finding.get('fieldPath')}" if finding.get("fieldPath") else ""))
        print(f"      {finding.get('detail', '')}")
for consumer in consumers:
    if str(consumer.get("level", "")).lower() in ("affected", "possible"):
        print(f"  {consumer.get('level')}: {consumer.get('consumer')} — {consumer.get('reason', '')}")

if decision == "PUBLISH":
    print("ci-check: PASS (no consumer-impacting breaking change)")
    sys.exit(0)

if decision == "PENDING_ACK":
    if strict:
        print("ci-check: FAIL (PENDING_ACK under --strict: breaking change still needs acknowledgement)")
        sys.exit(1)
    print("ci-check: PASS with warning (PENDING_ACK: breaking, but no AFFECTED consumer;"
          " the publish will still require a human acknowledgement)")
    sys.exit(0)

if decision == "BLOCK":
    print("ci-check: FAIL (BLOCKED: breaking change with AFFECTED consumer(s))")
    if required:
        print(f"  required action: {required}")
    sys.exit(1)

print(f"ci-check: FAIL (unexpected decision '{decision}')", file=sys.stderr)
sys.exit(3)
PYEOF
