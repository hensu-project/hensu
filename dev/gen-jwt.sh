#!/bin/bash
# Generates a short-lived RS256 JWT for local development.
# Requires: openssl, a private key at dev/keys/privateKey.pem
#
# Usage (from repo root):
#   bash dev/gen-jwt.sh [--groups GROUP[,GROUP...]] [--relay NAME]
#
#   --groups  comma-separated roles for the `groups` claim; default `hensu-api`,
#             or `hensu-relay` when --relay is given
#   --relay   relay name for the `hensu_relay` claim, [a-z0-9][a-z0-9-]{0,62}
#
# Examples:
#   bash dev/gen-jwt.sh                      # API token for /api/*
#   bash dev/gen-jwt.sh --relay default      # relay token for /mcp/v1/*
#
# The token carries the claims expected by the server:
#   iss       = https://hensu.io  (mp.jwt.verify.issuer)
#   tenant_id = dev-tenant        (%dev.hensu.tenant.default)
# Valid for 1 hour.

usage() {
    sed -n '5,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
}

GROUPS_ARG=""
RELAY=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --groups)
            [[ $# -ge 2 ]] || { echo "ERROR: --groups needs a value" >&2; exit 1; }
            GROUPS_ARG="$2"; shift 2 ;;
        --relay)
            [[ $# -ge 2 ]] || { echo "ERROR: --relay needs a value" >&2; exit 1; }
            RELAY="$2"; shift 2 ;;
        -h|--help)
            usage; exit 0 ;;
        *)
            echo "ERROR: unknown argument: $1" >&2; usage; exit 1 ;;
    esac
done

if [[ -n "$RELAY" && ! "$RELAY" =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]]; then
    echo "ERROR: --relay must match [a-z0-9][a-z0-9-]{0,62}" >&2
    exit 1
fi

if [[ -z "$GROUPS_ARG" ]]; then
    if [[ -n "$RELAY" ]]; then GROUPS_ARG="hensu-relay"; else GROUPS_ARG="hensu-api"; fi
fi

H_GROUPS=""
IFS=',' read -ra GROUP_LIST <<< "$GROUPS_ARG"
for group in "${GROUP_LIST[@]}"; do
    if [[ ! "$group" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
        echo "ERROR: invalid group '${group}': use lowercase letters, digits and hyphens" >&2
        exit 1
    fi
    H_GROUPS="${H_GROUPS:+${H_GROUPS},}\"${group}\""
done
H_GROUPS="[${H_GROUPS}]"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRIVATE_KEY="${SCRIPT_DIR}/keys/privateKey.pem"

if [[ ! -f "${PRIVATE_KEY}" ]]; then
    echo "ERROR: Private key not found at ${PRIVATE_KEY}" >&2
    echo "Generate a keypair first — see dev/README.md" >&2
    exit 1
fi

ISSUER="https://hensu.io"
SUBJECT="hensu-dev-user"
TENANT="dev-tenant"

url_encode() {
    openssl base64 -e -A | tr '+/' '-_' | tr -d '='
}

header=$(echo -n '{"alg":"RS256","typ":"JWT"}' | url_encode)

iat=$(date +%s)
exp=$((iat + 3600))

RELAY_CLAIM=""
if [[ -n "$RELAY" ]]; then
    RELAY_CLAIM=$(printf ',"hensu_relay":"%s"' "$RELAY")
fi

payload_json=$(printf '{"iss":"%s","sub":"%s","groups":%s,"tenant_id":"%s"%s,"iat":%d,"exp":%d}' \
    "$ISSUER" "$SUBJECT" "$H_GROUPS" "$TENANT" "$RELAY_CLAIM" "$iat" "$exp")

payload=$(echo -n "$payload_json" | url_encode)

signature=$(echo -n "${header}.${payload}" | openssl dgst -sha256 -sign "$PRIVATE_KEY" | url_encode)

echo "${header}.${payload}.${signature}"
