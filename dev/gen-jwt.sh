#!/bin/bash
# Generates a short-lived RS256 JWT for local development.
# Requires: openssl, a private key at dev/keys/privateKey.pem
#
# Usage (from repo root):
#   bash dev/gen-jwt.sh
#
# The token carries the claims expected by the server:
#   iss       = https://hensu.io  (mp.jwt.verify.issuer)
#   tenant_id = dev-tenant        (%dev.hensu.tenant.default)
# Valid for 1 hour.

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
H_GROUPS='["admin","user"]'

url_encode() {
    openssl base64 -e -A | tr '+/' '-_' | tr -d '='
}

header=$(echo -n '{"alg":"RS256","typ":"JWT"}' | url_encode)

iat=$(date +%s)
exp=$((iat + 3600))

payload_json=$(printf '{"iss":"%s","sub":"%s","groups":%s,"tenant_id":"%s","iat":%d,"exp":%d}' \
    "$ISSUER" "$SUBJECT" "$H_GROUPS" "$TENANT" "$iat" "$exp")

payload=$(echo -n "$payload_json" | url_encode)

signature=$(echo -n "${header}.${payload}" | openssl dgst -sha256 -sign "$PRIVATE_KEY" | url_encode)

echo "${header}.${payload}.${signature}"
