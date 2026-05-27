#!/usr/bin/env bash
set -euo pipefail

if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

if [ ! -d .venv ]; then
    echo "venv not found. Run ./install.sh first." >&2
    exit 1
fi
# shellcheck disable=SC1091
. .venv/bin/activate

mkdir -p "${SUZU_WORKSPACE_ROOT:-./workspace}" \
         "${SUZU_APK_ROOT:-./apk_artifacts}" \
         "$(dirname "${SUZU_LOG_FILE:-./logs/suzu.log}")"

exec uvicorn suzu_luminaris.main:app \
    --host "${SUZU_HOST:-0.0.0.0}" \
    --port "${SUZU_PORT:-8765}" \
    --log-level info
