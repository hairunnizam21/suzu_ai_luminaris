#!/usr/bin/env bash
# SuzuAI Luminaris backend installer. Idempotent — safe to re-run.
# Target: Ubuntu 22.04 / Debian 12 with 4+ GB RAM.

set -euo pipefail

SUDO=$([ "$EUID" -ne 0 ] && echo sudo || echo)

echo "[1/5] Installing system packages (python3.11, jdk-17, build deps)..."
$SUDO apt-get update -qq
$SUDO apt-get install -y --no-install-recommends \
    python3.11 python3.11-venv python3-pip \
    openjdk-17-jdk-headless \
    unzip wget curl git ca-certificates \
    sqlite3

echo "[2/5] Setting up python venv at .venv..."
if [ ! -d ".venv" ]; then
    python3.11 -m venv .venv
fi
# shellcheck disable=SC1091
. .venv/bin/activate
pip install --quiet --upgrade pip
pip install --quiet -e .

echo "[3/5] Installing apktool, jadx, apksigner..."
$SUDO mkdir -p /opt/apktool
APKTOOL_VERSION="2.9.3"
APKTOOL_JAR="/opt/apktool/apktool.jar"
if [ ! -f "$APKTOOL_JAR" ]; then
    $SUDO wget -q -O "$APKTOOL_JAR" \
        "https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_${APKTOOL_VERSION}.jar"
fi
$SUDO tee /usr/local/bin/apktool > /dev/null <<'EOF'
#!/usr/bin/env bash
exec java -jar /opt/apktool/apktool.jar "$@"
EOF
$SUDO chmod +x /usr/local/bin/apktool

JADX_VERSION="1.5.0"
if [ ! -x /opt/jadx/bin/jadx ]; then
    $SUDO mkdir -p /opt/jadx
    wget -q -O /tmp/jadx.zip \
        "https://github.com/skylot/jadx/releases/download/v${JADX_VERSION}/jadx-${JADX_VERSION}.zip"
    $SUDO unzip -q -o /tmp/jadx.zip -d /opt/jadx
    $SUDO ln -sf /opt/jadx/bin/jadx /usr/local/bin/jadx
    $SUDO ln -sf /opt/jadx/bin/jadx-gui /usr/local/bin/jadx-gui || true
    rm -f /tmp/jadx.zip
fi

# apksigner ships with the Android command-line tools. Lightweight install:
if ! command -v apksigner >/dev/null; then
    $SUDO mkdir -p /opt/android-sdk/cmdline-tools
    if [ ! -d /opt/android-sdk/cmdline-tools/latest ]; then
        wget -q -O /tmp/cmdline-tools.zip \
            "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        $SUDO unzip -q -o /tmp/cmdline-tools.zip -d /tmp/cmdline-tools
        $SUDO mv /tmp/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
        rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip
    fi
    export ANDROID_SDK_ROOT=/opt/android-sdk
    yes 2>/dev/null | $SUDO /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
        --sdk_root="$ANDROID_SDK_ROOT" --licenses > /dev/null || true
    $SUDO /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
        --sdk_root="$ANDROID_SDK_ROOT" "build-tools;34.0.0" > /dev/null
    $SUDO ln -sf "$ANDROID_SDK_ROOT/build-tools/34.0.0/apksigner" /usr/local/bin/apksigner
fi

echo "[4/5] Generating .env if missing..."
if [ ! -f .env ]; then
    TOKEN=$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 40)
    cp .env.example .env
    sed -i "s|SUZU_TOKEN=.*|SUZU_TOKEN=${TOKEN}|" .env
    echo "==> Generated SUZU_TOKEN=${TOKEN}"
    echo "    Paste this into the Server Panel APK on first launch."
fi

echo "[5/5] Done. Run ./run.sh to start FastAPI on :8765."
