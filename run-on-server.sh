#!/usr/bin/env bash
set -Eeuo pipefail

[[ -f .env ]] || { printf '錯誤: .env 不存在\n' >&2; exit 1; }
[[ -f linebot.json ]] || { printf '錯誤: linebot.json 不存在\n' >&2; exit 1; }
[[ -x scripts/deploy.sh ]] || chmod +x scripts/deploy.sh

set -a
# shellcheck disable=SC1091
source .env
set +a

export GOOGLE_CREDENTIALS_JSON
GOOGLE_CREDENTIALS_JSON="$(<linebot.json)"
export IMAGE="${IMAGE:-ghcr.io/alientechforge/linebot-translator:latest}"

exec scripts/deploy.sh
