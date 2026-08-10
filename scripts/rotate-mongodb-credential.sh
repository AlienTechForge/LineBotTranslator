#!/usr/bin/env bash
set -Eeuo pipefail

log() {
    printf '[mongodb-rotation] %s\n' "$*"
}

fail() {
    printf '[mongodb-rotation] ERROR: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command is unavailable: $1"
}

require_value() {
    local key="$1"
    [[ -n "${!key:-}" ]] || fail "Required setting is missing: $key"
}

validate_identifier() {
    local label="$1"
    local value="$2"
    [[ "$value" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || fail "$label contains unsupported characters"
}

mask_value() {
    local value="$1"
    if [[ "${GITHUB_ACTIONS:-false}" == 'true' && -n "$value" ]]; then
        printf '::add-mask::%s\n' "$value"
    fi
}

find_mongodb_container() {
    if [[ -n "${MONGODB_CONTAINER_NAME:-}" ]]; then
        validate_identifier 'MONGODB_CONTAINER_NAME' "$MONGODB_CONTAINER_NAME"
        docker container inspect "$MONGODB_CONTAINER_NAME" >/dev/null 2>&1 ||
            fail "MongoDB container does not exist: $MONGODB_CONTAINER_NAME"
        printf '%s\n' "$MONGODB_CONTAINER_NAME"
        return
    fi

    local candidates=()
    while IFS=$'\t' read -r name image; do
        if [[ "${image,,}" == *mongo* && "${image,,}" != *mongo-express* ]]; then
            candidates+=("$name")
        fi
    done < <(docker ps --format '{{.Names}}\t{{.Image}}')

    if (( ${#candidates[@]} != 1 )); then
        local candidate_names='none'
        if (( ${#candidates[@]} > 0 )); then
            candidate_names="$(IFS=,; printf '%s' "${candidates[*]}")"
        fi
        fail "Expected exactly one running MongoDB container; found ${#candidates[@]} (${candidate_names}). Set MONGODB_CONTAINER_NAME explicitly."
    fi

    printf '%s\n' "${candidates[0]}"
}

read_container_environment() {
    local container="$1"
    local key="$2"
    docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container" |
        sed -n "s/^${key}=//p" |
        tail -n 1
}

verify_uri_authenticates() {
    local uri="$1"
    docker run --rm --network host mongo:7 \
        mongosh "$uri" --quiet \
        --eval 'const result = db.runCommand({ping: 1}); if (result.ok !== 1) { quit(1); }' \
        >/dev/null 2>&1
}

root_mongosh() {
    local javascript="$1"
    printf '%s\n' "$mongodb_root_password" |
        docker exec -i \
            -e ROTATION_USERNAME \
            -e ROTATION_AUTH_DB \
            -e ROTATION_PASSWORD \
            "$mongodb_container" \
            mongosh --quiet \
            --username "$mongodb_root_username" \
            --password \
            --authenticationDatabase admin \
            --eval "$javascript" \
            >/dev/null
}

change_application_password() {
    local password="$1"
    ROTATION_PASSWORD="$password"
    export ROTATION_PASSWORD
    root_mongosh '
        const target = db.getSiblingDB(process.env.ROTATION_AUTH_DB);
        const result = target.runCommand({
            updateUser: process.env.ROTATION_USERNAME,
            pwd: process.env.ROTATION_PASSWORD
        });
        if (result.ok !== 1) { quit(1); }
    '
}

require_command docker
require_value ROTATION_MODE
require_value ROTATION_CONFIRMATION
require_value MONGODB_CURRENT_URI
require_value MONGODB_ROTATION_OLD_URI
require_value ROTATION_USERNAME
require_value ROTATION_AUTH_DB

[[ "$ROTATION_CONFIRMATION" == 'ROTATE_EXPOSED_MONGODB_CREDENTIAL' ]] ||
    fail 'Confirmation phrase does not match'
[[ "$ROTATION_MODE" == 'audit' || "$ROTATION_MODE" == 'rotate' ]] ||
    fail 'ROTATION_MODE must be audit or rotate'
validate_identifier 'ROTATION_USERNAME' "$ROTATION_USERNAME"
validate_identifier 'ROTATION_AUTH_DB' "$ROTATION_AUTH_DB"

mask_value "$MONGODB_CURRENT_URI"
mask_value "$MONGODB_ROTATION_OLD_URI"
mask_value "${MONGODB_ROTATION_NEW_URI:-}"
mask_value "${MONGODB_ROTATION_OLD_PASSWORD:-}"
mask_value "${MONGODB_ROTATION_NEW_PASSWORD:-}"

mongodb_container="$(find_mongodb_container)"
mongodb_root_username="$(read_container_environment "$mongodb_container" 'MONGO_INITDB_ROOT_USERNAME')"
mongodb_root_password="$(read_container_environment "$mongodb_container" 'MONGO_INITDB_ROOT_PASSWORD')"
[[ -n "$mongodb_root_username" && -n "$mongodb_root_password" ]] ||
    fail 'The MongoDB container does not expose the supported root credential environment names'
mask_value "$mongodb_root_username"
mask_value "$mongodb_root_password"

export ROTATION_USERNAME ROTATION_AUTH_DB
ROTATION_PASSWORD=''
export ROTATION_PASSWORD
root_mongosh 'const result = db.runCommand({ping: 1}); if (result.ok !== 1) { quit(1); }'

if [[ "$ROTATION_MODE" == 'audit' ]]; then
    [[ "$MONGODB_CURRENT_URI" == "$MONGODB_ROTATION_OLD_URI" ]] ||
        fail 'The deployed GitHub secret does not match the credential captured in the incident evidence'
    verify_uri_authenticates "$MONGODB_ROTATION_OLD_URI" ||
        fail 'The exposed MongoDB credential no longer authenticates'
    log "Audit passed: expected credential is active and root-controlled rotation is available through container $mongodb_container"
    exit 0
fi

require_value MONGODB_ROTATION_NEW_URI
require_value MONGODB_ROTATION_OLD_PASSWORD
require_value MONGODB_ROTATION_NEW_PASSWORD
require_value IMAGE
[[ "$MONGODB_ROTATION_OLD_URI" != "$MONGODB_ROTATION_NEW_URI" ]] ||
    fail 'Old and new MongoDB URIs must differ'
[[ "$MONGODB_ROTATION_OLD_PASSWORD" != "$MONGODB_ROTATION_NEW_PASSWORD" ]] ||
    fail 'Old and new MongoDB passwords must differ'
[[ "$MONGODB_CURRENT_URI" == "$MONGODB_ROTATION_NEW_URI" ]] ||
    fail 'MONGODB_URI must be updated to the new value before rotation'
verify_uri_authenticates "$MONGODB_ROTATION_OLD_URI" ||
    fail 'Old credential failed pre-rotation authentication'
if verify_uri_authenticates "$MONGODB_ROTATION_NEW_URI"; then
    fail 'New credential unexpectedly authenticates before rotation'
fi

rotation_applied='false'
rotation_committed='false'
rollback_rotation() {
    local status=$?
    trap - EXIT
    if [[ "$status" -ne 0 && "$rotation_applied" == 'true' && "$rotation_committed" != 'true' ]]; then
        set +e
        log 'Restoring the previous MongoDB password after rotation failure'
        change_application_password "$MONGODB_ROTATION_OLD_PASSWORD"
        if verify_uri_authenticates "$MONGODB_ROTATION_OLD_URI"; then
            log 'Previous MongoDB credential restored'
        else
            log 'ERROR: automatic MongoDB credential restoration could not be verified'
        fi
    fi
    exit "$status"
}
trap rollback_rotation EXIT

log 'Changing the application MongoDB password through the local database container'
change_application_password "$MONGODB_ROTATION_NEW_PASSWORD"
rotation_applied='true'

verify_uri_authenticates "$MONGODB_ROTATION_NEW_URI" ||
    fail 'New credential failed post-rotation authentication'
if verify_uri_authenticates "$MONGODB_ROTATION_OLD_URI"; then
    fail 'Old credential still authenticates after rotation'
fi

log 'Credential checks passed; redeploying the application with the new URI'
MONGODB_URI="$MONGODB_ROTATION_NEW_URI" scripts/deploy.sh
rotation_committed='true'

if verify_uri_authenticates "$MONGODB_ROTATION_OLD_URI"; then
    fail 'Old credential unexpectedly authenticated after deployment'
fi
verify_uri_authenticates "$MONGODB_ROTATION_NEW_URI" ||
    fail 'New credential failed final authentication check'

log 'Rotation completed: application is healthy and the exposed credential is rejected'
