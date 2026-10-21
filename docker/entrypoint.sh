#!/usr/bin/env bash
# Container entrypoint: one container, two processes.
#
#   1. the image-translator service (Python, 127.0.0.1:4041 by default) is started first and must answer
#      GET /healthz within IMAGE_TRANSLATOR_STARTUP_TIMEOUT seconds, so the bot never starts without it;
#   2. the Java bot is started next.
#
# When either process exits the other one gets SIGTERM (SIGKILL after ENTRYPOINT_STOP_GRACE seconds) and the
# container exits with the status of the first one, so the Docker restart policy restarts both. SIGTERM and
# SIGINT are forwarded to both for a graceful stop. OCR_ENABLED=false starts the bot alone.
#
# Settings read here (the image sets the defaults): IMAGE_TRANSLATOR_BIND, IMAGE_TRANSLATOR_PORT,
# IMAGE_TRANSLATOR_DATA_DIR, IMAGE_TRANSLATOR_URL, IMAGE_TRANSLATOR_TOKEN (generated when empty), OCR_ENABLED.
# Everything else (ANTHROPIC_API_KEY, OPEN_ROUTE_API_KEY, GOOGLE_APPLICATION_CREDENTIALS, CLAUDE_MODEL,
# CLAUDE_JUDGE_MODEL, OPENROUTER_IMAGE_MODEL, the budgets, SEND_MIN_USABILITY, V1_DEADLINE_S, LAB_TOKEN,
# LAB_MOCK) is inherited from the container environment by both processes; the service has its own defaults.
# Test hooks: IMAGE_TRANSLATOR_HOME, IMAGE_TRANSLATOR_PYTHON, IMAGE_TRANSLATOR_STARTUP_TIMEOUT, ENTRYPOINT_STOP_GRACE.
set -Eeuo pipefail

SERVICE_HOME="${IMAGE_TRANSLATOR_HOME:-/app/image-translator}"
SERVICE_PYTHON="${IMAGE_TRANSLATOR_PYTHON:-/opt/image-translator/bin/python}"
STARTUP_TIMEOUT="${IMAGE_TRANSLATOR_STARTUP_TIMEOUT:-30}"
STOP_GRACE="${ENTRYPOINT_STOP_GRACE:-20}"

work_dir=''
service_pid=''
java_pid=''
logger_pid=''
stop_signal=''

log() {
    printf '[entrypoint] %s\n' "$*"
}

fail() {
    printf '[entrypoint] ERROR: %s\n' "$*" >&2
    exit 1
}

is_false() {
    case "${1,,}" in
        0|false|no|off) return 0 ;;
        *) return 1 ;;
    esac
}

cleanup() {
    [[ -z "$work_dir" ]] || rm -rf -- "$work_dir"
}
trap cleanup EXIT
trap 'stop_signal=TERM' TERM
trap 'stop_signal=INT' INT

IMAGE_TRANSLATOR_BIND="${IMAGE_TRANSLATOR_BIND:-127.0.0.1}"
IMAGE_TRANSLATOR_PORT="${IMAGE_TRANSLATOR_PORT:-4041}"
IMAGE_TRANSLATOR_DATA_DIR="${IMAGE_TRANSLATOR_DATA_DIR:-/app/image-translator/data}"
IMAGE_TRANSLATOR_URL="${IMAGE_TRANSLATOR_URL:-http://127.0.0.1:${IMAGE_TRANSLATOR_PORT}}"
if [[ ! "$IMAGE_TRANSLATOR_PORT" =~ ^[0-9]+$ ]] || (( 10#$IMAGE_TRANSLATOR_PORT < 1 || 10#$IMAGE_TRANSLATOR_PORT > 65535 )); then
    fail 'IMAGE_TRANSLATOR_PORT must be between 1 and 65535'
fi
if [[ ! "$STARTUP_TIMEOUT" =~ ^[0-9]+$ ]] || [[ ! "$STOP_GRACE" =~ ^[0-9]+$ ]]; then
    fail 'IMAGE_TRANSLATOR_STARTUP_TIMEOUT and ENTRYPOINT_STOP_GRACE must be whole seconds'
fi
export IMAGE_TRANSLATOR_BIND IMAGE_TRANSLATOR_PORT IMAGE_TRANSLATOR_DATA_DIR IMAGE_TRANSLATOR_URL

if [[ -z "${IMAGE_TRANSLATOR_TOKEN:-}" ]]; then
    IMAGE_TRANSLATOR_TOKEN="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
    log 'IMAGE_TRANSLATOR_TOKEN is not set: generated a random token for this start (it is not logged)'
fi
export IMAGE_TRANSLATOR_TOKEN

service_enabled=true
if is_false "${OCR_ENABLED:-true}"; then
    service_enabled=false
    export IMAGE_TRANSLATOR_HEALTH=skip
    log 'OCR_ENABLED=false: the image-translator service is not started'
fi

service_url() {
    local host="$IMAGE_TRANSLATOR_BIND"
    case "$host" in
        ''|0.0.0.0|::) host=127.0.0.1 ;;
        *:*) host="[$host]" ;;
        *) ;;
    esac
    printf 'http://%s:%s/healthz' "$host" "$IMAGE_TRANSLATOR_PORT"
}

run_service() {
    export SERVICE_TOKEN="$IMAGE_TRANSLATOR_TOKEN"
    export BIND_HOST="$IMAGE_TRANSLATOR_BIND"
    export PORT="$IMAGE_TRANSLATOR_PORT"
    export LAB_DATA_DIR="$IMAGE_TRANSLATOR_DATA_DIR"
    export PYTHONUNBUFFERED=1
    cd "$SERVICE_HOME"
    exec "$SERVICE_PYTHON" -m app
}

start_service() {
    [[ -x "$SERVICE_PYTHON" ]] || fail "The image-translator interpreter is missing: $SERVICE_PYTHON"
    [[ -d "$SERVICE_HOME" ]] || fail "The image-translator directory is missing: $SERVICE_HOME"
    work_dir="$(mktemp -d)"
    mkfifo "$work_dir/service.log"
    sed -u 's/^/[image-translator] /' < "$work_dir/service.log" &
    logger_pid=$!
    run_service > "$work_dir/service.log" 2>&1 &
    service_pid=$!
    log "image-translator started (pid $service_pid), waiting up to ${STARTUP_TIMEOUT}s for /healthz"
}

# 0 = healthy, 1 = no answer in time, 2 = the process exited, 3 = a stop signal arrived
wait_for_service() {
    local url deadline
    url="$(service_url)"
    deadline=$((SECONDS + STARTUP_TIMEOUT))
    while (( SECONDS < deadline )); do
        [[ -z "$stop_signal" ]] || return 3
        kill -0 "$service_pid" 2>/dev/null || return 2
        if curl --fail --silent --max-time 2 --output /dev/null "$url"; then
            return 0
        fi
        sleep 0.5
    done
    return 1
}

alive_children() {
    local pid
    for pid in "$service_pid" "$java_pid"; do
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
    done
    return 1
}

terminate_children() {
    local pid deadline
    for pid in "$service_pid" "$java_pid"; do
        if [[ -n "$pid" ]]; then
            kill -TERM "$pid" 2>/dev/null || true
        fi
    done
    deadline=$((SECONDS + STOP_GRACE))
    while alive_children && (( SECONDS < deadline )); do
        sleep 0.2
    done
    if alive_children; then
        log "children still running after ${STOP_GRACE}s: sending SIGKILL"
        for pid in "$service_pid" "$java_pid"; do
            if [[ -n "$pid" ]]; then
                kill -KILL "$pid" 2>/dev/null || true
            fi
        done
    fi
}

drain_service_log() {
    local deadline
    [[ -n "$logger_pid" ]] || return 0
    deadline=$((SECONDS + 3))
    while kill -0 "$logger_pid" 2>/dev/null && (( SECONDS < deadline )); do
        sleep 0.1
    done
    kill "$logger_pid" 2>/dev/null || true
}

finish() {
    local status="$1"
    terminate_children
    drain_service_log
    log "exiting with status $status"
    exit "$status"
}

signal_status() {
    case "$stop_signal" in
        INT) printf '130' ;;
        *) printf '143' ;;
    esac
}

if [[ "$service_enabled" == true ]]; then
    start_service
    wait_status=0
    wait_for_service || wait_status=$?
    case "$wait_status" in
        0)
            log 'image-translator is healthy'
            ;;
        1)
            printf '[entrypoint] ERROR: image-translator did not answer /healthz within %ss\n' "$STARTUP_TIMEOUT" >&2
            finish 1
            ;;
        2)
            service_status=0
            wait "$service_pid" || service_status=$?
            printf '[entrypoint] ERROR: image-translator exited during startup with status %s\n' "$service_status" >&2
            service_pid=''
            finish "$(( service_status == 0 ? 1 : service_status ))"
            ;;
        *)
            log "received SIG${stop_signal}: stopping"
            finish "$(signal_status)"
            ;;
    esac
fi

if [[ -n "$stop_signal" ]]; then
    log "received SIG${stop_signal}: stopping"
    finish "$(signal_status)"
fi

java -jar /app/app.jar &
java_pid=$!
log "bot started (pid $java_pid)"

pids=("$java_pid")
if [[ -n "$service_pid" ]]; then
    pids+=("$service_pid")
fi

while :; do
    if [[ -n "$stop_signal" ]]; then
        log "received SIG${stop_signal}: stopping"
        finish "$(signal_status)"
    fi
    sleep 1 &
    tick=$!
    finished=''
    child_status=0
    wait -n -p finished "$tick" "${pids[@]}" || child_status=$?
    case "${finished:-}" in
        '') ;;
        "$tick") ;;
        "$java_pid")
            if [[ -n "$service_pid" ]]; then
                log "bot exited with status $child_status: stopping the image-translator"
            else
                log "bot exited with status $child_status"
            fi
            java_pid=''
            finish "$child_status"
            ;;
        *)
            log "image-translator exited with status $child_status: stopping the bot"
            service_pid=''
            finish "$child_status"
            ;;
    esac
done
