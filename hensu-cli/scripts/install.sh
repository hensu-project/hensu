#!/usr/bin/env bash
# install.sh — Hensu CLI installer
#
# Usage:
#   curl -sSL https://github.com/hensu-project/hensu/releases/latest/download/install.sh | bash
#   bash install.sh [--prefix /usr/local] [--no-service]
#
# Options:
#   --prefix DIR       Installation prefix for the 'hensu' wrapper (default: ~/.local)
#   --no-service       Skip service installation (systemd on Linux, launchd on macOS)
#   --version VER      Install a specific release tag (default: latest)
#   --help             Show this help and exit
#
# What this script does:
#   1. Verifies Java 25+ is available (required for virtual-thread support)
#   2. Downloads the hensu-cli runner JAR from GitHub Releases
#   3. Installs the JAR to  $HENSU_HOME/lib/hensu.jar  (~/.hensu by default)
#   4. Writes a 'hensu' launcher script to $PREFIX/bin/hensu
#   5. Optionally installs a platform service unit so the daemon starts on login:
#      - Linux:  systemd user service  (~/.config/systemd/user/hensu-daemon.service)
#      - macOS:  launchd user agent    (~/Library/LaunchAgents/io.hensu.daemon.plist)
#
# Uninstall:
#   Run scripts/remove.sh  (or bash <(curl -sSL https://github.com/hensu-project/hensu/releases/latest/download/remove.sh))

set -euo pipefail

# ——— Defaults ————————————————————————————————————————————————————————————————

GITHUB_REPO="hensu-project/hensu"
HENSU_HOME="${HENSU_HOME:-${HOME}/.hensu}"
PREFIX="${PREFIX:-${HOME}/.local}"
INSTALL_SERVICE=true
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
        --prefix)    PREFIX="$2";      shift 2 ;;
        --no-service) INSTALL_SERVICE=false; shift ;;
        --version)   VERSION="$2";    shift 2 ;;
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

# ——— Step 1: Java version check ——————————————————————————————————————————————

printf "\n$(bold 'Hensu CLI installer')\n\n"
info "Checking Java runtime..."

if ! command -v java &>/dev/null; then
    die "Java not found. Hensu requires Java 25+.\n  Download: https://adoptium.net"
fi

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
JAVA_MAJOR=$(echo "$JAVA_VERSION" | cut -d. -f1)

if [[ "$JAVA_MAJOR" -lt 25 ]]; then
    die "Java ${JAVA_VERSION} found, but Hensu requires Java 25+.\n  Download: https://adoptium.net"
fi

ok "Java ${JAVA_VERSION}"

# ——— Step 2: Resolve release version —————————————————————————————————————————

if [[ -z "$VERSION" ]]; then
    info "Fetching latest CLI release..."
    # /releases/latest ignores pre-releases, so query all releases and pick the
    # newest tag matching cli/v* (includes alpha/beta/rc pre-releases).
    RELEASES_URL="https://api.github.com/repos/${GITHUB_REPO}/releases"
    if command -v curl &>/dev/null; then
        VERSION=$(curl -sSf "$RELEASES_URL" \
                  | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/' \
                  | grep '^cli/v' | head -1)
    elif command -v wget &>/dev/null; then
        VERSION=$(wget -qO- "$RELEASES_URL" \
                  | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/' \
                  | grep '^cli/v' | head -1)
    else
        die "curl or wget is required to download releases."
    fi
fi

[[ -z "$VERSION" ]] && die "Could not determine release version. Use --version to specify one."

# VERSION may be a full tag (cli/v0.1.0-beta.1) or bare semver (v0.1.0-beta.1)
if [[ "$VERSION" == cli/* ]]; then
    RELEASE_TAG="${VERSION}"
    SEMVER="${VERSION#cli/}"
else
    RELEASE_TAG="cli/${VERSION}"
    SEMVER="${VERSION}"
fi
ok "Version: ${SEMVER}"

# ——— Step 3: Download JAR ————————————————————————————————————————————————————

JAR_URL="https://github.com/${GITHUB_REPO}/releases/download/${RELEASE_TAG}/hensu-cli-${SEMVER}-runner.jar"

info "Downloading from GitHub Releases..."
info "${JAR_URL}"

mkdir -p "${LIB_DIR}"

TMP_JAR="$(mktemp /tmp/hensu-XXXXXX.jar)"
trap 'rm -f "$TMP_JAR"' EXIT

if command -v curl &>/dev/null; then
    curl -sSfL "$JAR_URL" -o "$TMP_JAR" || die "Download failed: ${JAR_URL}"
else
    wget -qO "$TMP_JAR" "$JAR_URL" || die "Download failed: ${JAR_URL}"
fi

mv "$TMP_JAR" "$JAR"
echo "${SEMVER}" > "${LIB_DIR}/version"
ok "Downloaded hensu.jar"

# ——— Step 4: Write wrapper script ————————————————————————————————————————————

mkdir -p "${BIN_DIR}"

cat > "${WRAPPER}" <<EOF
#!/usr/bin/env bash
# Hensu CLI launcher — generated by install.sh
# Do not edit: re-run install.sh to update.

HENSU_HOME="\${HENSU_HOME:-\${HOME}/.hensu}"
export HENSU_JAR="\${HENSU_HOME}/lib/hensu.jar"

# Forward COLUMNS so daemon output is width-aware
export COLUMNS="\${COLUMNS:-\$(tput cols 2>/dev/null || echo 80)}"

exec java \\
    --enable-native-access=ALL-UNNAMED \\
    --sun-misc-unsafe-memory-access=allow \\
    -jar "\${HENSU_JAR}" \\
    "\$@"
EOF

chmod +x "${WRAPPER}"
ok "Wrote launcher: ${WRAPPER}"

# ——— Step 5: Platform service unit (optional) ————————————————————————————————

CREDENTIALS_FILE="${HENSU_HOME}/credentials"
OS="$(uname -s)"

if [[ "$INSTALL_SERVICE" == "false" ]]; then
    info "Skipping service installation (--no-service)."
elif [[ "$OS" == "Darwin" ]]; then
    # macOS — install a launchd user agent
    LAUNCHD_DIR="${HOME}/Library/LaunchAgents"
    PLIST_FILE="${LAUNCHD_DIR}/io.hensu.daemon.plist"

    info "Installing launchd user agent..."
    mkdir -p "${LAUNCHD_DIR}"

    cat > "${PLIST_FILE}" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>io.hensu.daemon</string>
    <key>ProgramArguments</key>
    <array>
        <string>${WRAPPER}</string>
        <string>daemon</string>
        <string>start</string>
        <string>--foreground</string>
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <key>HENSU_HOME</key>
        <string>${HENSU_HOME}</string>
        <key>HENSU_JAR</key>
        <string>${JAR}</string>
    </dict>
    <key>RunAtLoad</key>
    <false/>
    <key>KeepAlive</key>
    <false/>
    <key>StandardOutPath</key>
    <string>${HENSU_HOME}/daemon.log</string>
    <key>StandardErrorPath</key>
    <string>${HENSU_HOME}/daemon.log</string>
</dict>
</plist>
EOF

    ok "Installed: ${PLIST_FILE}"

    printf "\n  Manage the user agent (macOS):\n"
    printf "    ${GRAY}launchctl load -w ${PLIST_FILE}${RESET}   # enable + auto-start on login\n"
    printf "    ${GRAY}launchctl start io.hensu.daemon${RESET}   # start now\n"
    printf "    ${GRAY}launchctl list io.hensu.daemon${RESET}    # check status\n"
elif command -v systemctl &>/dev/null; then
    # Linux — install a systemd user service
    SYSTEMD_DIR="${HOME}/.config/systemd/user"
    SOCKET_FILE="${SYSTEMD_DIR}/hensu-daemon.socket"
    SERVICE_FILE="${SYSTEMD_DIR}/hensu-daemon.service"

    info "Installing systemd user service..."
    mkdir -p "${SYSTEMD_DIR}"

    # Socket unit — systemd pre-binds the Unix socket and activates the service on demand
    cat > "${SOCKET_FILE}" <<EOF
[Unit]
Description=Hensu Daemon Socket

[Socket]
ListenStream=${HENSU_HOME}/daemon.sock
SocketMode=0600
DirectoryMode=0700

[Install]
WantedBy=sockets.target
EOF

    # Service unit — Type=notify: systemctl start blocks until Quarkus sends READY=1
    cat > "${SERVICE_FILE}" <<EOF
[Unit]
Description=Hensu Workflow Daemon
Documentation=https://github.com/${GITHUB_REPO}
Requires=hensu-daemon.socket
After=hensu-daemon.socket network.target

[Service]
Type=notify
# Java NIO cannot write to UNIX SOCK_DGRAM directly; sdNotifyReady() delegates
# to the systemd-notify binary spawned as a child of the main JVM process.
# NotifyAccess=all is required because the child PID is not a systemd control
# process (exec) but a runtime fork — only "all" covers cgroup members.
NotifyAccess=all
Environment="HENSU_HOME=%h/.hensu"
# EnvironmentFile loads ~/.hensu/credentials into the process env as a fallback.
# The primary credential source is the file itself (read at startup by CredentialsLoader).
EnvironmentFile=-%h/.hensu/credentials
ExecStart=${WRAPPER} daemon start --foreground
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
LimitNOFILE=65536

[Install]
WantedBy=default.target
EOF

    systemctl --user daemon-reload
    ok "Installed: ${SOCKET_FILE}"
    ok "Installed: ${SERVICE_FILE}"

    printf "\n  Manage the user service (note: --user flag required):\n"
    printf "    ${GRAY}systemctl --user enable --now hensu-daemon.socket${RESET}  # auto-activate on login\n"
    printf "    ${GRAY}systemctl --user start hensu-daemon${RESET}                # start now\n"
    printf "    ${GRAY}systemctl --user status hensu-daemon${RESET}               # check status\n"
else
    info "No supported service manager found — skipping service installation."
fi

# Create credentials file with instructions if it doesn't exist yet
if [[ ! -f "${CREDENTIALS_FILE}" ]]; then
    mkdir -p "${HENSU_HOME}"
    cat > "${CREDENTIALS_FILE}" <<'EOF'
# Hensu credentials — add your API keys below
# Format: KEY=VALUE (one per line, # for comments)
#
# GOOGLE_API_KEY=AIza...
# ANTHROPIC_API_KEY=sk-ant-...
# OPENAI_API_KEY=sk-...
EOF
    ok "Created: ${CREDENTIALS_FILE}"
    warn "Add your API keys to ${CREDENTIALS_FILE}"
fi

# ——— Step 6: PATH hint ———————————————————————————————————————————————————————

# Check if BIN_DIR is already on PATH
if [[ ":${PATH}:" != *":${BIN_DIR}:"* ]]; then
    printf "\n${YELLOW}  Add ${BIN_DIR} to your PATH:${RESET}\n"

    SHELL_RC=""
    case "${SHELL}" in
        */zsh)  SHELL_RC="${HOME}/.zshrc"  ;;
        */bash) SHELL_RC="${HOME}/.bashrc" ;;
        */fish) SHELL_RC="${HOME}/.config/fish/config.fish" ;;
    esac

    if [[ -n "$SHELL_RC" ]]; then
        printf "    ${GRAY}echo 'export PATH=\"${BIN_DIR}:\$PATH\"' >> ${SHELL_RC}${RESET}\n"
        printf "    ${GRAY}source ${SHELL_RC}${RESET}\n"
    else
        printf "    ${GRAY}export PATH=\"${BIN_DIR}:\$PATH\"${RESET}\n"
    fi
fi

# ——— Done ——————————————————————————————————————————————————————————————————

printf "\n  ${GREEN}✓ Hensu ${SEMVER} installed successfully!${RESET}\n\n"
printf "  Quick start:\n"
printf "    ${GRAY}hensu daemon start${RESET}\n"
printf "    ${GRAY}hensu run my-workflow${RESET}\n"
printf "    ${GRAY}hensu ps${RESET}\n\n"
