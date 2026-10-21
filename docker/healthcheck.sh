#!/usr/bin/env bash
# Container HEALTHCHECK: the bot must be ready and, unless it is switched off, the image-translator must answer.
#
# The service is skipped when OCR_ENABLED is false (the entrypoint does not start it then) or when
# IMAGE_TRANSLATOR_HEALTH=skip is set in the container environment. A health check runs as a separate process
# started from the container configuration, so it cannot see variables that the entrypoint exported at run
# time: the decision has to come from variables that are part of the container configuration.
set -u

service_host="${IMAGE_TRANSLATOR_BIND:-127.0.0.1}"
case "$service_host" in
    ''|0.0.0.0|::) service_host=127.0.0.1 ;;
    *:*) service_host="[$service_host]" ;;
    *) ;;
esac

if ! curl --fail --silent --show-error --max-time 4 --output /dev/null \
    "http://127.0.0.1:${SERVER_PORT:-4040}/actuator/health/readiness"; then
    echo 'bot readiness check failed' >&2
    exit 1
fi

ocr_enabled="${OCR_ENABLED:-true}"
case "${ocr_enabled,,}" in
    0|false|no|off) exit 0 ;;
    *) ;;
esac
[[ "${IMAGE_TRANSLATOR_HEALTH:-}" != skip ]] || exit 0

if ! curl --fail --silent --show-error --max-time 4 --output /dev/null \
    "http://${service_host}:${IMAGE_TRANSLATOR_PORT:-4041}/healthz"; then
    echo 'image-translator /healthz check failed' >&2
    exit 1
fi
