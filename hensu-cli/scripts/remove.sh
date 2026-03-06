#!/usr/bin/env bash
# remove.sh — Hensu CLI uninstaller
#
# Usage:
#   bash remove.sh [--prefix /usr/local] [--purge]
#
# Options:
#   --prefix DIR   Installation prefix used during install (default: ~/.local)
#   --purge        Also remove ~/.hensu/ (daemon socket, logs, workflow data)
#   --help         Show this help and exit
#
# What this script does:
#   1. Stops the running daemon gracefully (if any)
#   2. Removes the platform service unit (systemd on Linux, launchd on macOS)
#   3. Removes the 'hensu' wrapper script from $PREFIX/bin
#   4. Removes the hensu.jar from ~/.hensu/lib
#   5. With --purge: removes all of ~/.hensu/ (socket, PID, logs, workflow data)

set -euo pipefail

# ——— Defaults ————————————————————————————————————————————————————————————————

HENSU_HOME="${HENSU_HOME:-${HOME}/.hensu}"
PREFIX="${PREFIX:-${HOME}/.local}"
PURGE=false

# ——— Helpers —————————————————————————————————————————————————————————————————

RED="\033[0;31m"; YELLOW="\033[1;33m"; GREEN="\033[0;32m"
GRAY="\033[0;37m"; BOLD="\033[1m"; RESET="\033[0m"

info()  { printf "  ${GRAY}%s${RESET}\n" "$*"; }
ok()    { printf "  ${GREEN}✓${RESET} %s\n" "$*"; }
warn()  { printf "  ${YELLOW}⚠${RESET}  %s\n" "$*"; }
skip()  { printf "  ${GRAY}—${RESET}  %s\n" "$*"; }
die()   { printf "\n${RED}✗ Error:${RESET} %s\n\n" "$*" >&2; exit 1; }
bold()  { printf "${BOLD}%s${RESET}" "$*"; }

confirm() {
    # confirm "Question?" → returns 0 for yes, 1 for no
    local prompt="$1"
    printf "  ${YELLOW}?${RESET}  %s [y/N] " "$prompt"
    read -r answer </dev/tty
    [[ "$answer" =~ ^[Yy]$ ]]
}

# ——— Argument parsing —————————————————————————————————————————————————————————

while [[ $# -gt 0 ]]; do
    case "$1" in
        --prefix) PREFIX="$2"; shift 2 ;;
        --purge)  PURGE=true;  shift   ;;
        --help|-h)
            sed -n '/^# /p' "$0" | sed 's/^# //'
            exit 0
            ;;
        *) die "Unknown option: $1" ;;
    esac
done

BIN_DIR="${PREFIX}/bin"
WRAPPER="${BIN_DIR}/hensu"
JAR="${HENSU_HOME}/lib/hensu.jar"
DAEMON_SOCKET="${HENSU_HOME}/daemon.sock"
OS="$(uname -s)"

# ——— Start ———————————————————————————————————————————————————————————————————

printf "\n$(bold 'Hensu CLI uninstaller')\n\n"

# ——— Step 1: Stop daemon —————————————————————————————————————————————————————

if [[ -S "$DAEMON_SOCKET" ]]; then
    info "Daemon socket found — stopping daemon..."

    # Use the installed wrapper if available; fall back to java -jar directly
    if [[ -x "$WRAPPER" ]]; then
        "$WRAPPER" daemon stop 2>/dev/null && ok "Daemon stopped" || warn "Daemon stop returned non-zero (already gone?)"
    elif [[ -f "$JAR" ]] && command -v java &>/dev/null; then
        java -jar "$JAR" daemon stop 2>/dev/null && ok "Daemon stopped" || warn "Daemon stop returned non-zero (already gone?)"
    else
        # Last resort: delete the socket so the daemon detects it and self-terminates
        warn "Could not find 'hensu' binary to stop daemon gracefully."
        warn "Removing socket — daemon will detect this and exit on next I/O."
        rm -f "$DAEMON_SOCKET"
    fi

    # Give the daemon up to 3 seconds to clean up
    for i in 1 2 3; do
        [[ -S "$DAEMON_SOCKET" ]] || break
        sleep 1
    done
    [[ -S "$DAEMON_SOCKET" ]] && rm -f "$DAEMON_SOCKET" && warn "Force-removed stale socket."
else
    skip "No daemon socket found (daemon not running)."
fi

# ——— Step 2: Remove platform service unit ————————————————————————————————————

if [[ "$OS" == "Darwin" ]]; then
    PLIST_FILE="${HOME}/Library/LaunchAgents/io.hensu.daemon.plist"

    if [[ -f "$PLIST_FILE" ]]; then
        info "Removing launchd user agent..."
        launchctl unload -w "$PLIST_FILE" 2>/dev/null || true
        rm -f "$PLIST_FILE"
        ok "Removed: ${PLIST_FILE}"
    else
        skip "No launchd agent installed."
    fi
else
    SYSTEMD_DIR="${HOME}/.config/systemd/user"
    SYSTEMD_SERVICE="${SYSTEMD_DIR}/hensu-daemon.service"
    SYSTEMD_SOCKET="${SYSTEMD_DIR}/hensu-daemon.socket"

    if [[ -f "$SYSTEMD_SERVICE" ]] || [[ -f "$SYSTEMD_SOCKET" ]]; then
        info "Removing systemd user service..."

        if command -v systemctl &>/dev/null; then
            # Stop and disable both units — the socket must be stopped first or it
            # will re-trigger the service on the next incoming connection.
            systemctl --user disable --now hensu-daemon.socket hensu-daemon.service 2>/dev/null || true
            systemctl --user daemon-reload
        fi

        rm -f "$SYSTEMD_SERVICE" "$SYSTEMD_SOCKET"
        ok "Removed: ${SYSTEMD_SERVICE}"
        ok "Removed: ${SYSTEMD_SOCKET}"
    else
        skip "No systemd service installed."
    fi
fi

# ——— Step 3: Remove wrapper script ———————————————————————————————————————————

if [[ -f "$WRAPPER" ]]; then
    rm -f "$WRAPPER"
    ok "Removed: ${WRAPPER}"
else
    skip "Wrapper not found at ${WRAPPER}."
fi

# ——— Step 4: Remove JAR —————————————————————————————————————————————————————

if [[ -f "$JAR" ]]; then
    rm -f "$JAR"
    ok "Removed: ${JAR}"
else
    skip "JAR not found at ${JAR}."
fi

# Remove lib dir only if it's now empty
if [[ -d "${HENSU_HOME}/lib" ]] && [[ -z "$(ls -A "${HENSU_HOME}/lib" 2>/dev/null)" ]]; then
    rmdir "${HENSU_HOME}/lib"
fi

# ——— Step 5: Purge data directory ————————————————————————————————————————————

if [[ "$PURGE" == "true" ]]; then
    if [[ -d "$HENSU_HOME" ]]; then
        info "Purging ${HENSU_HOME} ..."
        rm -rf "$HENSU_HOME"
        ok "Removed: ${HENSU_HOME}"
    else
        skip "Data directory ${HENSU_HOME} does not exist."
    fi
else
    if [[ -d "$HENSU_HOME" ]]; then
        printf "\n  ${YELLOW}Data directory retained:${RESET} ${HENSU_HOME}\n"
        printf "  ${GRAY}It contains workflow data and daemon logs.\n"
        printf "  Run with --purge to remove it.${RESET}\n"

        if confirm "Remove ${HENSU_HOME} now?"; then
            rm -rf "$HENSU_HOME"
            ok "Removed: ${HENSU_HOME}"
        else
            skip "Kept ${HENSU_HOME}."
        fi
    fi
fi

# ——— Done ———————————————————————————————————————————————————————————————————

printf "\n  ${GREEN}✓ Hensu removed.${RESET}\n\n"
