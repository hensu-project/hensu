#!/usr/bin/env bash
# update.sh — Hensu CLI updater
#
# Usage:
#   curl -sSL https://github.com/hensu-project/hensu/releases/latest/download/update.sh | bash
#   bash hensu-cli/scripts/update.sh [--prefix /usr/local] [--version VER]
#
# Options:
#   --prefix DIR       Installation prefix used during install (default: ~/.local)
#   --version VER      Target version to install (default: latest)
#   --help             Show this help and exit
#
# What this script does:
#   1. Reads the installed version from $HENSU_HOME/lib/version
#   2. Fetches the latest release tag from GitHub
#   3. If already up-to-date, exits with a message
#   4. Downloads the new hensu-cli runner JAR and replaces the installed one
#   5. Restarts the systemd user service if it was running
#
# Install / uninstall:
#   Run scripts/install.sh  (or bash <(curl -sSL https://github.com/hensu-project/hensu/releases/latest/download/install.sh))
#   Run scripts/remove.sh   (or bash <(curl -sSL https://github.com/hensu-project/hensu/releases/latest/download/remove.sh))

set -euo pipefail

# ——— Defaults ————————————————————————————————————————————————————————————————

GITHUB_REPO="hensu-project/hensu"
HENSU_HOME="${HENSU_HOME:-${HOME}/.hensu}"
PREFIX="${PREFIX:-${HOME}/.local}"
VERSION=""

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
        --prefix)  PREFIX="$2";  shift 2 ;;
        --version) VERSION="$2"; shift 2 ;;
        --help|-h)
            sed -n '/^# /p' "$0" | sed 's/^# //'
            exit 0
            ;;
        *) die "Unknown option: $1" ;;
    esac
done

BIN_DIR="${PREFIX}/bin"
LIB_DIR="${HENSU_HOME}/lib"
WRAPPER="${BIN_DIR}/hensu"
JAR="${LIB_DIR}/hensu.jar"
VERSION_FILE="${LIB_DIR}/version"
DAEMON_SOCKET="${HENSU_HOME}/daemon.sock"

# ——— Preflight check —————————————————————————————————————————————————————————

printf "\n$(bold 'Hensu CLI updater')\n\n"

[[ -f "$JAR" ]] || die "Hensu is not installed at ${JAR}.\n  Run install.sh first."

# ——— Step 1: Read installed version ——————————————————————————————————————————

CURRENT_VERSION=""
if [[ -f "$VERSION_FILE" ]]; then
    CURRENT_VERSION=$(cat "$VERSION_FILE")
fi

if [[ -n "$CURRENT_VERSION" ]]; then
    info "Installed version: ${CURRENT_VERSION}"
else
    info "Installed version: unknown (pre-update.sh install)"
fi

# ——— Step 2: Resolve target version ——————————————————————————————————————————

if [[ -z "$VERSION" ]]; then
    info "Fetching latest release..."
    if command -v curl &>/dev/null; then
        VERSION=$(curl -sSf "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" \
                  | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
    elif command -v wget &>/dev/null; then
        VERSION=$(wget -qO- "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" \
                  | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
    else
        die "curl or wget is required to check for updates."
    fi
fi

[[ -z "$VERSION" ]] && die "Could not determine latest version. Use --version to specify one."
info "Latest version:    ${VERSION}"

# ——— Step 3: Compare versions ————————————————————————————————————————————————

if [[ "$CURRENT_VERSION" == "$VERSION" ]]; then
    printf "\n  ${GREEN}✓ Already up to date (${VERSION}).${RESET}\n\n"
    exit 0
fi

printf "\n  Updating ${CURRENT_VERSION:-unknown} → ${VERSION}\n\n"

# ——— Step 4: Stop daemon if running ——————————————————————————————————————————

DAEMON_WAS_RUNNING=false

if [[ -S "$DAEMON_SOCKET" ]]; then
    info "Stopping daemon for upgrade..."
    DAEMON_WAS_RUNNING=true

    if [[ -x "$WRAPPER" ]]; then
        "$WRAPPER" daemon stop 2>/dev/null \
            && ok "Daemon stopped" \
            || warn "Daemon stop returned non-zero (already gone?)"
    elif [[ -f "$JAR" ]] && command -v java &>/dev/null; then
        java -jar "$JAR" daemon stop 2>/dev/null \
            && ok "Daemon stopped" \
            || warn "Daemon stop returned non-zero (already gone?)"
    else
        warn "Cannot stop daemon gracefully — no java or wrapper found."
    fi

    # Wait up to 3 s for the socket to disappear
    for i in 1 2 3; do
        [[ -S "$DAEMON_SOCKET" ]] || break
        sleep 1
    done
fi

# ——— Step 5: Download new JAR ————————————————————————————————————————————————

JAR_URL="https://github.com/${GITHUB_REPO}/releases/download/${VERSION}/hensu-cli-${VERSION}-runner.jar"

info "Downloading from GitHub Releases..."
info "${JAR_URL}"

TMP_JAR="$(mktemp /tmp/hensu-XXXXXX.jar)"
trap 'rm -f "$TMP_JAR"' EXIT

if command -v curl &>/dev/null; then
    curl -sSfL "$JAR_URL" -o "$TMP_JAR" || die "Download failed: ${JAR_URL}"
else
    wget -qO "$TMP_JAR" "$JAR_URL" || die "Download failed: ${JAR_URL}"
fi

mv "$TMP_JAR" "$JAR"
echo "${VERSION}" > "${VERSION_FILE}"
ok "Updated hensu.jar → ${VERSION}"

# ——— Step 6: Restart daemon if it was running ————————————————————————————————

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

# ——— Done ———————————————————————————————————————————————————————————————————

printf "\n  ${GREEN}✓ Hensu updated to ${VERSION}!${RESET}\n\n"
