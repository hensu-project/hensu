#!/usr/bin/env bash
# update-dev.sh — Hensu CLI local-build updater (dev/test only)
#
# Usage:
#   bash hensu-cli/scripts/update-dev.sh [--prefix ~/.local] [--jar PATH]
#
# Options:
#   --prefix DIR       Installation prefix used during install (default: ~/.local)
#   --jar PATH         Path to quarkus-run.jar (default: <repo>/hensu-cli/build/quarkus-app/quarkus-run.jar)
#   --help             Show this help and exit
#
# What this script does:
#   1. Verifies a previous dev install exists
#   2. Stops the daemon if it is running
#   3. Replaces $HENSU_HOME/lib/quarkus-app/ with the local build
#   4. Restarts the daemon if it was running
#
# NOTE: This is the LOCAL development variant — no GitHub download.
#       For production updates use update.sh instead.

set -euo pipefail

# ——— Defaults ————————————————————————————————————————————————————————————————

HENSU_HOME="${HENSU_HOME:-${HOME}/.hensu}"
PREFIX="${PREFIX:-${HOME}/.local}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOCAL_JAR="${REPO_ROOT}/hensu-cli/build/quarkus-app/quarkus-run.jar"

# ——— Helpers —————————————————————————————————————————————————————————————————

RED="\033[0;31m"; YELLOW="\033[1;33m"; GREEN="\033[0;32m"
GRAY="\033[0;37m"; BOLD="\033[1m"; RESET="\033[0m"

info()  { printf "  ${GRAY}%s${RESET}\n" "$*"; }
ok()    { printf "  ${GREEN}✓${RESET} %s\n" "$*"; }
warn()  { printf "  ${YELLOW}⚠${RESET}  %s\n" "$*"; }
die()   { printf "\n${RED}✗ Error:${RESET} %s\n\n" "$*" >&2; exit 1; }
bold()  { printf "${BOLD}%s${RESET}" "$*"; }

# ——— Argument parsing —————————————————————————————————————————————————————————

while [[ $# -gt 0 ]]; do
    case "$1" in
        --prefix) PREFIX="$2";    shift 2 ;;
        --jar)    LOCAL_JAR="$2"; shift 2 ;;
        --help|-h)
            sed -n '/^# /p' "$0" | sed 's/^# //'
            exit 0
            ;;
        *) die "Unknown option: $1" ;;
    esac
done

BIN_DIR="${PREFIX}/bin"
LIB_DIR="${HENSU_HOME}/lib"
APP_DIR="${LIB_DIR}/quarkus-app"
WRAPPER="${BIN_DIR}/hensu"
DAEMON_SOCKET="${HENSU_HOME}/daemon.sock"

# ——— Preflight check —————————————————————————————————————————————————————————

printf "\n$(bold 'Hensu CLI updater') ${YELLOW}[dev/local build]${RESET}\n\n"

[[ -d "$APP_DIR" ]] || die "Dev install not found at ${APP_DIR}.\n  Run install-dev.sh first."

# ——— Step 1: Validate local build ————————————————————————————————————————————

info "Checking local build at: ${LOCAL_JAR}"

[[ -f "$LOCAL_JAR" ]] || die "JAR not found: ${LOCAL_JAR}\n  Run: ./gradlew :hensu-cli:quarkusBuild"

LOCAL_APP_DIR="$(dirname "$LOCAL_JAR")"

[[ -d "${LOCAL_APP_DIR}/lib" ]] || \
    die "Quarkus app directory incomplete — missing lib/ next to quarkus-run.jar.\n  Re-run: ./gradlew :hensu-cli:quarkusBuild"

ok "Local build found: ${LOCAL_APP_DIR}"

# ——— Step 2: Stop daemon if running ——————————————————————————————————————————

DAEMON_WAS_RUNNING=false

if [[ -S "$DAEMON_SOCKET" ]]; then
    info "Stopping daemon for update..."
    DAEMON_WAS_RUNNING=true

    if [[ -x "$WRAPPER" ]]; then
        "$WRAPPER" daemon stop 2>/dev/null \
            && ok "Daemon stopped" \
            || warn "Daemon stop returned non-zero (already gone?)"
    else
        warn "No wrapper found at ${WRAPPER} — skipping graceful stop."
    fi

    # Wait up to 3 s for the socket to disappear
    for i in 1 2 3; do
        [[ -S "$DAEMON_SOCKET" ]] || break
        sleep 1
    done
fi

# ——— Step 3: Replace quarkus-app directory ———————————————————————————————————

info "Updating ${APP_DIR}..."

rm -rf "${APP_DIR}"
cp -r "${LOCAL_APP_DIR}" "${APP_DIR}"

echo "dev-local" > "${LIB_DIR}/version"
ok "Updated quarkus-app"

# ——— Step 4: Restart daemon if it was running ————————————————————————————————

if [[ "$DAEMON_WAS_RUNNING" == "true" ]]; then
    OS="$(uname -s)"
    if [[ "$OS" == "Darwin" ]]; then
        PLIST_FILE="${HOME}/Library/LaunchAgents/io.hensu.daemon.plist"
        if [[ -f "$PLIST_FILE" ]] && launchctl list io.hensu.daemon &>/dev/null 2>&1; then
            info "Restarting launchd user agent..."
            launchctl stop io.hensu.daemon 2>/dev/null || true
            launchctl start io.hensu.daemon && ok "Agent restarted."
        else
            info "Daemon was running — start it again when ready:"
            printf "    ${GRAY}hensu daemon start${RESET}\n"
        fi
    elif command -v systemctl &>/dev/null \
       && systemctl --user is-enabled hensu-daemon &>/dev/null 2>&1; then
        info "Restarting systemd service..."
        systemctl --user restart hensu-daemon && ok "Service restarted."
    else
        info "Daemon was running — start it again when ready:"
        printf "    ${GRAY}hensu daemon start${RESET}\n"
    fi
fi

# ——— Done ————————————————————————————————————————————————————————————————————

printf "\n  ${GREEN}✓ Hensu dev build updated!${RESET}\n\n"
