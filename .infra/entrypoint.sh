#!/usr/bin/env bash
set -euo pipefail

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    printf '%s\n' "Required file is missing: ${path}" >&2
    exit 1
  fi
}

require_file /config/selfhost.yml
require_file /config/selfhost-secrets.yml

secret_property='-Dsecrets.bundle.filename=/config/selfhost-secrets.yml'
if [[ " ${JAVA_TOOL_OPTIONS:-} " != *" ${secret_property} "* ]]; then
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+${JAVA_TOOL_OPTIONS} }${secret_property}"
fi

exec java ${JAVA_OPTS:-} -jar /app/TextSecureServer.jar "$@"
