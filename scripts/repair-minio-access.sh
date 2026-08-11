#!/usr/bin/env bash
set -Eeuo pipefail

readonly MC_IMAGE='minio/mc:RELEASE.2025-08-13T08-35-41Z'
readonly POLICY_NAME='linebot-translator-rw'
readonly MINIO_CONTAINER="${MINIO_CONTAINER:-minio}"

fail() {
    printf 'MinIO access repair failed: %s\n' "$*" >&2
    exit 1
}

require_value() {
    local name="$1"
    [[ -v "$name" && -n "${!name}" ]] || fail "$name is required"
    [[ "${!name}" != *$'\n'* && "${!name}" != *$'\r'* ]] || fail "$name contains a newline"
}

container_env() {
    local container="$1"
    local key="$2"
    docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container" |
        awk -F= -v expected="$key" '$1 == expected {sub(/^[^=]*=/, ""); print; exit}'
}

require_value MINIO_ACCESS_KEY
require_value MINIO_SECRET_KEY
require_value MINIO_BUCKET_NAME
[[ "$MINIO_CONTAINER" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ ]] || fail 'MINIO_CONTAINER is invalid'
[[ "$MINIO_BUCKET_NAME" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || fail 'MINIO_BUCKET_NAME is invalid'
[[ "${#MINIO_ACCESS_KEY}" -ge 3 ]] || fail 'MINIO_ACCESS_KEY is too short'
[[ "${#MINIO_SECRET_KEY}" -ge 8 ]] || fail 'MINIO_SECRET_KEY is too short'

docker inspect "$MINIO_CONTAINER" >/dev/null 2>&1 || fail "container $MINIO_CONTAINER does not exist"
[[ "$(docker inspect --format '{{.State.Running}}' "$MINIO_CONTAINER")" == 'true' ]] ||
    fail "container $MINIO_CONTAINER is not running"

admin_access_key="$(container_env "$MINIO_CONTAINER" MINIO_ROOT_USER)"
admin_secret_key="$(container_env "$MINIO_CONTAINER" MINIO_ROOT_PASSWORD)"
if [[ -z "$admin_access_key" ]]; then
    admin_access_key="$(container_env "$MINIO_CONTAINER" MINIO_ACCESS_KEY)"
fi
if [[ -z "$admin_secret_key" ]]; then
    admin_secret_key="$(container_env "$MINIO_CONTAINER" MINIO_SECRET_KEY)"
fi
[[ -n "$admin_access_key" && -n "$admin_secret_key" ]] ||
    fail 'MinIO administrative credentials are unavailable in the container environment'

if [[ "$MINIO_ACCESS_KEY" == "$admin_access_key" && "$MINIO_SECRET_KEY" != "$admin_secret_key" ]]; then
    fail 'GitHub MINIO_SECRET_KEY does not match the MinIO root identity'
fi

policy_json="$(printf '%s' "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"s3:GetBucketLocation\",\"s3:ListBucket\",\"s3:ListBucketMultipartUploads\"],\"Resource\":[\"arn:aws:s3:::$MINIO_BUCKET_NAME\"]},{\"Effect\":\"Allow\",\"Action\":[\"s3:PutObject\",\"s3:GetObject\",\"s3:DeleteObject\",\"s3:AbortMultipartUpload\",\"s3:ListMultipartUploadParts\"],\"Resource\":[\"arn:aws:s3:::$MINIO_BUCKET_NAME/*\"]}]}")"
export ADMIN_ACCESS_KEY="$admin_access_key"
export ADMIN_SECRET_KEY="$admin_secret_key"
export APP_ACCESS_KEY="$MINIO_ACCESS_KEY"
export APP_SECRET_KEY="$MINIO_SECRET_KEY"
export APP_BUCKET="$MINIO_BUCKET_NAME"
export POLICY_NAME
export PROBE_ID="${GITHUB_RUN_ID:-manual}"
export APP_POLICY_JSON="$policy_json"

printf 'Running credential-safe MinIO IAM repair for bucket=%s\n' "$MINIO_BUCKET_NAME"
docker run --rm \
    --network host \
    --env ADMIN_ACCESS_KEY \
    --env ADMIN_SECRET_KEY \
    --env APP_ACCESS_KEY \
    --env APP_SECRET_KEY \
    --env APP_BUCKET \
    --env POLICY_NAME \
    --env PROBE_ID \
    --env APP_POLICY_JSON \
    --entrypoint /bin/sh \
    "$MC_IMAGE" \
    -eu -c '
        mc alias set admin http://127.0.0.1:9000 "$ADMIN_ACCESS_KEY" "$ADMIN_SECRET_KEY" >/dev/null
        mc admin info admin >/dev/null

        mc alias set app http://127.0.0.1:9000 "$APP_ACCESS_KEY" "$APP_SECRET_KEY" >/dev/null 2>&1 || true
        if mc ls "app/$APP_BUCKET" >/dev/null 2>&1; then
            printf "Pre-repair application probe: pass\n"
        else
            printf "Pre-repair application probe: access denied\n"
        fi

        mc mb --ignore-existing "admin/$APP_BUCKET" >/dev/null
        if [ "$APP_ACCESS_KEY" != "$ADMIN_ACCESS_KEY" ]; then
            mc admin user add admin "$APP_ACCESS_KEY" "$APP_SECRET_KEY" >/dev/null
            mc admin user enable admin "$APP_ACCESS_KEY" >/dev/null
            printf "%s\n" "$APP_POLICY_JSON" > /tmp/linebot-policy.json
            mc admin policy create admin "$POLICY_NAME" /tmp/linebot-policy.json >/dev/null
            mc admin policy attach admin "$POLICY_NAME" --user "$APP_ACCESS_KEY" >/dev/null
        fi

        mc alias set app http://127.0.0.1:9000 "$APP_ACCESS_KEY" "$APP_SECRET_KEY" >/dev/null
        probe="app/$APP_BUCKET/.linebot-health/permission-$PROBE_ID.txt"
        printf "linebot-minio-permission-probe\n" | mc pipe "$probe" >/dev/null
        mc stat --no-list "$probe" >/dev/null
        mc cat "$probe" >/dev/null
        mc rm "$probe" >/dev/null
        printf "Post-repair application CRUD probe: pass\n"
    '

printf 'MinIO application IAM repair completed without exposing credentials\n'
