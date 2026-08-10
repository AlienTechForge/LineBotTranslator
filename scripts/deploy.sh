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
AI_DEFAULT_PROVIDER="${AI_DEFAULT_PROVIDER:-openai}"

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

case "$AI_DEFAULT_PROVIDER" in
    openai) require_value OPENAI_API_KEY ;;
    gemini) require_value GEMINI_API_KEY ;;
    *) fail 'AI_DEFAULT_PROVIDER must be openai or gemini' ;;
esac

if is_true "$OCR_ENABLED"; then
    require_value GOOGLE_CREDENTIALS_JSON
fi

runtime_keys=(
    LINE_BOT_CHANNEL_TOKEN
    LINE_BOT_CHANNEL_SECRET
    MONGODB_URI
    MONGODB_DATABASE
    OPENAI_API_KEY
    OPENAI_MODEL_NAME
    OPENAI_AVAILABLE_MODELS
    OPENAI_API_URL
    GEMINI_API_KEY
    GEMINI_MODEL_NAME
    GEMINI_AVAILABLE_MODELS
    OCR_ENABLED
    AI_DEFAULT_PROVIDER
    ADMIN_USERS
    MINIO_ENDPOINT
    MINIO_ACCESS_KEY
    MINIO_SECRET_KEY
    MINIO_BUCKET_NAME
    LANGUAGE_DETECTION_USE_AI
    LANGUAGE_DETECTION_AI_PROVIDER
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
