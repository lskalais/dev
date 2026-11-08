#!/usr/bin/env bash
set -euo pipefail

MARIADB_SERVICE="${MARIADB_SERVICE:-mariadb}"
TOMCAT_HOME="${TOMCAT_HOME:-/opt/tomcat}"
CATALINA="${TOMCAT_HOME}/bin/catalina.sh"

if [[ ! -x "$CATALINA" ]]; then
    printf 'ERROR: Tomcat shutdown script is not executable: %s\n' "$CATALINA" >&2
    printf 'Set TOMCAT_HOME to your Tomcat installation directory.\n' >&2
    exit 1
fi

run_systemctl() {
    if (( EUID == 0 )); then
        systemctl "$@"
    elif command -v sudo >/dev/null 2>&1; then
        sudo systemctl "$@"
    else
        printf 'ERROR: Stop MariaDB as root or install sudo to manage its service.\n' >&2
        return 1
    fi
}

printf 'Stopping Tomcat...\n'
export CATALINA_HOME="$TOMCAT_HOME"
"$CATALINA" stop -wait 30

if systemctl is-active --quiet "$MARIADB_SERVICE"; then
    printf 'Stopping MariaDB service "%s"...\n' "$MARIADB_SERVICE"
    run_systemctl stop "$MARIADB_SERVICE"
else
    printf 'MariaDB service "%s" is not running.\n' "$MARIADB_SERVICE"
fi
