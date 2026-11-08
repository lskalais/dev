#!/usr/bin/env bash
set -euo pipefail

MARIADB_SERVICE="${MARIADB_SERVICE:-mariadb}"
TOMCAT_HOME="${TOMCAT_HOME:-/opt/tomcat}"
DB_PORT="${DB_PORT:-3306}"
CATALINA="${TOMCAT_HOME}/bin/catalina.sh"

if [[ ! -x "$CATALINA" ]]; then
    printf 'ERROR: Tomcat startup script is not executable: %s\n' "$CATALINA" >&2
    printf 'Set TOMCAT_HOME to your Tomcat installation directory.\n' >&2
    exit 1
fi

if [[ ! "$DB_PORT" =~ ^[0-9]+$ ]] || (( DB_PORT < 1 || DB_PORT > 65535 )); then
    printf 'ERROR: Invalid MariaDB port: %s\n' "$DB_PORT" >&2
    exit 1
fi

run_systemctl() {
    if (( EUID == 0 )); then
        systemctl "$@"
    elif command -v sudo >/dev/null 2>&1; then
        sudo systemctl "$@"
    else
        printf 'ERROR: Start MariaDB as root or install sudo to manage its service.\n' >&2
        return 1
    fi
}

printf 'Starting MariaDB service "%s"...\n' "$MARIADB_SERVICE"
run_systemctl start "$MARIADB_SERVICE"

printf 'Waiting for MariaDB on port %s...\n' "$DB_PORT"
database_ready=false
for ((attempt = 0; attempt < 30; attempt++)); do
    if (echo >"/dev/tcp/127.0.0.1/$DB_PORT") >/dev/null 2>&1; then
        database_ready=true
        break
    fi
    sleep 1
done

if [[ "$database_ready" != true ]]; then
    printf 'ERROR: MariaDB did not respond on 127.0.0.1:%s after 30 seconds.\n' "$DB_PORT" >&2
    exit 1
fi

printf 'MariaDB is ready. Starting Tomcat in the foreground; press Ctrl+C to stop it.\n'
export CATALINA_HOME="$TOMCAT_HOME"
exec "$CATALINA" run
