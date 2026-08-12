#!/usr/bin/env bash
set -Eeuo pipefail

log() {
    printf '[deploy] %s\n' "$*"
}

fail() {
    printf '[deploy] ERROR: %s\n' "$*" >&2
    exit 1
}

is_true() {
    case "${1,,}" in
        1|true|yes|on) return 0 ;;
        *) return 1 ;;
    esac
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command is unavailable: $1"
}

require_value() {
    local key="$1"
    [[ -n "${!key:-}" ]] || fail "Required runtime setting is missing: $key"
}

validate_openrouter() {
    local status
    status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
        --connect-timeout 10 --max-time 30 \
        --header "Authorization: Bearer $OPEN_ROUTE_API_KEY" \
        "${OPEN_ROUTE_API_URL%/}/models?output_modalities=text")"
    [[ "$status" == "200" ]] || fail "OpenRouter credential validation failed (HTTP $status)"
    log 'OpenRouter credential validation passed'
}

validate_identifier() {
    local label="$1"
    local value="$2"
    [[ "$value" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || fail "$label contains unsupported characters"
}

wait_for_health() {
    local container="$1"
    local timeout="$2"
    local require_healthcheck="$3"
    local elapsed=0
    local previous_state=''

    while (( elapsed < timeout )); do
        local running
        local health
        local state
        running="$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null || true)"
        health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$container" 2>/dev/null || true)"
        state="running=${running:-missing}, health=${health:-missing}"

        if [[ "$state" != "$previous_state" ]]; then
            log "Waiting for readiness: $state, elapsed=${elapsed}s"
            previous_state="$state"
        fi

        if [[ "$running" == "true" && "$health" == "healthy" ]]; then
            return 0
        fi
        if [[ "$running" == "true" && "$health" == "missing" && "$require_healthcheck" == "false" ]]; then
            return 0
        fi

        sleep 5
        elapsed=$((elapsed + 5))
    done

    return 1
}

report_container_diagnostics() {
    local container="$1"

    log 'Container status at readiness failure:'
    docker ps -a \
        --filter "name=^/${container}$" \
        --format 'name={{.Names}} status={{.Status}} image={{.Image}}' || true

    log 'Recent health checks:'
    docker inspect \
        --format '{{range .State.Health.Log}}{{.Start}} exit={{.ExitCode}} output={{printf "%q" .Output}}{{println}}{{end}}' \
        "$container" 2>/dev/null || true

    log 'Recent application logs:'
    docker logs --tail 200 "$container" 2>&1 || true
}

require_command docker

IMAGE="${IMAGE:-}"
CONTAINER_NAME="${CONTAINER_NAME:-linebot-translator}"
DOCKER_NETWORK="${DOCKER_NETWORK:-host}"
SERVER_PORT="${SERVER_PORT:-4040}"
HOST_PORT="${HOST_PORT:-$SERVER_PORT}"
LOG_VOLUME="${LOG_VOLUME:-linebot-translator-logs}"
CREDENTIALS_VOLUME="${CREDENTIALS_VOLUME:-linebot-translator-secrets}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-120}"
PULL_IMAGE="${PULL_IMAGE:-true}"
OCR_ENABLED="${OCR_ENABLED:-true}"
OPEN_ROUTE_API_URL="${OPEN_ROUTE_API_URL:-https://openrouter.ai/api/v1}"
VALIDATE_OPENROUTER_REMOTE="${VALIDATE_OPENROUTER_REMOTE:-true}"

[[ -n "$IMAGE" ]] || fail 'IMAGE must be an immutable image reference'
validate_identifier 'CONTAINER_NAME' "$CONTAINER_NAME"
validate_identifier 'DOCKER_NETWORK' "$DOCKER_NETWORK"
validate_identifier 'LOG_VOLUME' "$LOG_VOLUME"
validate_identifier 'CREDENTIALS_VOLUME' "$CREDENTIALS_VOLUME"
if [[ ! "$SERVER_PORT" =~ ^[0-9]+$ ]] || (( SERVER_PORT < 1 || SERVER_PORT > 65535 )); then
    fail 'SERVER_PORT must be between 1 and 65535'
fi
if [[ ! "$HOST_PORT" =~ ^[0-9]+$ ]] || (( HOST_PORT < 1 || HOST_PORT > 65535 )); then
    fail 'HOST_PORT must be between 1 and 65535'
fi
if [[ ! "$HEALTH_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || (( HEALTH_TIMEOUT_SECONDS < 10 )); then
    fail 'HEALTH_TIMEOUT_SECONDS must be at least 10'
fi

require_value LINE_BOT_CHANNEL_TOKEN
require_value LINE_BOT_CHANNEL_SECRET
require_value MONGODB_URI
require_value MONGODB_DATABASE

require_value OPEN_ROUTE_API_KEY
if is_true "$VALIDATE_OPENROUTER_REMOTE"; then
    require_command curl
    validate_openrouter
fi

if is_true "$OCR_ENABLED"; then
    require_value GOOGLE_CREDENTIALS_JSON
fi

runtime_keys=(
    LINE_BOT_CHANNEL_TOKEN
    LINE_BOT_CHANNEL_SECRET
    MONGODB_URI
    MONGODB_DATABASE
    MONGODB_CONNECT_TIMEOUT_MS
    MONGODB_READ_TIMEOUT_MS
    MONGODB_SERVER_SELECTION_TIMEOUT_MS
    MONGODB_HEARTBEAT_FREQUENCY_MS
    MONGODB_MIN_HEARTBEAT_FREQUENCY_MS
    TRANSLATION_CACHE_TTL
    TRANSLATION_CACHE_MAX_ENTRIES
    TRANSLATION_GLOSSARY_VERSION
    WEBHOOK_CORE_THREADS
    WEBHOOK_MAX_THREADS
    WEBHOOK_QUEUE_CAPACITY
    WEBHOOK_RECEIPT_TTL
    WEBHOOK_PROCESSING_LEASE
    WEBHOOK_REPLY_MAX_ATTEMPTS
    WEBHOOK_REPLY_RETRY_BACKOFF
    OPEN_ROUTE_API_KEY
    OPEN_ROUTE_MODEL_NAME
    OPEN_ROUTE_API_URL
    OCR_ENABLED
    ADMIN_USERS
    MINIO_ENDPOINT
    MINIO_PUBLIC_ENDPOINT
    MINIO_ACCESS_KEY
    MINIO_SECRET_KEY
    MINIO_BUCKET_NAME
    MINIO_REGION
    MINIO_ENABLED
    MINIO_RETRY_INTERVAL_MS
    MINIO_CONNECT_TIMEOUT_MS
    MINIO_WRITE_TIMEOUT_MS
    MINIO_READ_TIMEOUT_MS
    IMAGE_TRANSLATION_MAX_FILE_SIZE_BYTES
    IMAGE_TRANSLATION_MAX_DIMENSION
    IMAGE_TRANSLATION_MAX_PIXELS
    IMAGE_TRANSLATION_LOW_CONFIDENCE_THRESHOLD
    IMAGE_TRANSLATION_OVERLAY_ENABLED
    IMAGE_TRANSLATION_MAX_REGION_AREA_RATIO
    IMAGE_TRANSLATION_MAX_TOTAL_MASK_RATIO
    IMAGE_TRANSLATION_CLEANUP_INTERVAL
    IMAGE_PROXY_PUBLIC_BASE_URL
    IMAGE_PROXY_TOKEN_TTL
    IMAGE_PROXY_MAX_ENTRIES
    IMAGE_PROXY_PREVIEW_MAX_BYTES
    IMAGE_PROXY_CONTENT_CACHE_TTL
    IMAGE_PROXY_CONTENT_CACHE_ENTRIES
    SHORT_URL_ENABLED
    DWZ_API_BASE_URL
    DWZ_API_TOKEN
    DWZ_SHORT_DOMAIN
    DWZ_WORKSPACE_ID
    SHORT_URL_TTL
    LANGUAGE_DETECTION_USE_AI
    LANGUAGE_DETECTION_DEFAULT_CHINESE
    APP_BROADCAST_TEST_MODE
)

temporary_directory="$(mktemp -d)"
runtime_env="$temporary_directory/runtime.env"
backup_container=''
deployment_committed='false'

cleanup() {
    local status=$?
    rm -rf -- "$temporary_directory"

    if [[ "$status" -ne 0 && "$deployment_committed" != 'true' && -n "$backup_container" ]]; then
        set +e
        docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1
        docker rename "$backup_container" "$CONTAINER_NAME" >/dev/null 2>&1
        docker start "$CONTAINER_NAME" >/dev/null 2>&1
        log 'Previous container restored after deployment error'
    fi
}
trap cleanup EXIT

umask 077
for key in "${runtime_keys[@]}"; do
    if [[ -v "$key" ]]; then
        value="${!key}"
        [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || fail "$key cannot contain a newline"
        printf '%s=%s\n' "$key" "$value" >> "$runtime_env"
    fi
done
{
    printf 'SPRING_PROFILES_ACTIVE=prod\nSERVER_PORT=%s\n' "$SERVER_PORT"
    printf 'GRPC_NETTY_SHADED_NETTY_TCNATIVE_DO_NOT_USE_CONSCRYPT=true\n'
    printf 'GRPC_NETTY_SHADED_NETTY_TCNATIVE_DO_NOT_USE_NATIVE=true\n'
} >> "$runtime_env"

if is_true "$PULL_IMAGE"; then
    log "Pulling $IMAGE"
    docker pull "$IMAGE" >/dev/null
fi

credential_arguments=()
if is_true "$OCR_ENABLED"; then
    docker volume create "$CREDENTIALS_VOLUME" >/dev/null
    printf '%s' "$GOOGLE_CREDENTIALS_JSON" |
        docker run --rm -i --user 0:0 --entrypoint sh \
            -v "$CREDENTIALS_VOLUME:/run/secrets" "$IMAGE" \
            -c 'umask 077; cat > /run/secrets/linebot.json; chown 10001:10001 /run/secrets/linebot.json; chmod 0400 /run/secrets/linebot.json'
    printf 'GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/linebot.json\n' >> "$runtime_env"
    credential_arguments=(-v "$CREDENTIALS_VOLUME:/run/secrets:ro")
fi

docker volume create "$LOG_VOLUME" >/dev/null

network_arguments=(--network "$DOCKER_NETWORK")
port_arguments=()
if [[ "$DOCKER_NETWORK" != 'host' ]]; then
    docker network inspect "$DOCKER_NETWORK" >/dev/null 2>&1 || fail "Docker network does not exist: $DOCKER_NETWORK"
    port_arguments=(-p "$HOST_PORT:$SERVER_PORT")
fi

if docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
    backup_container="${CONTAINER_NAME}-rollback-$(date +%s)"
    docker container inspect "$backup_container" >/dev/null 2>&1 && fail "Rollback container already exists: $backup_container"
    log "Preserving current container as $backup_container"
    docker stop --time 30 "$CONTAINER_NAME" >/dev/null
    docker rename "$CONTAINER_NAME" "$backup_container"
fi

log "Starting $CONTAINER_NAME from immutable image $IMAGE"
docker run -d \
    --name "$CONTAINER_NAME" \
    --restart unless-stopped \
    "${network_arguments[@]}" \
    "${port_arguments[@]}" \
    --env-file "$runtime_env" \
    -v "$LOG_VOLUME:/app/logs" \
    "${credential_arguments[@]}" \
    --label com.alientechforge.repository=AlienTechForge/LineBotTranslator \
    "$IMAGE" >/dev/null

if ! wait_for_health "$CONTAINER_NAME" "$HEALTH_TIMEOUT_SECONDS" true; then
    health_status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$CONTAINER_NAME" 2>/dev/null || true)"
    report_container_diagnostics "$CONTAINER_NAME"
    fail "New container did not become ready (health=$health_status)"
fi

deployment_committed='true'
if [[ -n "$backup_container" ]]; then
    docker rm "$backup_container" >/dev/null
    backup_container=''
fi

log "Deployment is ready: $CONTAINER_NAME ($IMAGE)"
