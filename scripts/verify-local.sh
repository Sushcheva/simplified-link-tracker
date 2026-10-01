#!/usr/bin/env bash
# Requires Java 25, Python 3 and local PostgreSQL binaries. Uses only a temporary database.
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
check_dir="$(mktemp -d "${TMPDIR:-/tmp}/linktracker-check.XXXXXX")"
read -r pg_port app_port fixture_port < <(python3 - <<'PY'
import socket
sockets = [socket.socket() for _ in range(3)]
for sock in sockets:
    sock.bind(('127.0.0.1', 0))
print(*(sock.getsockname()[1] for sock in sockets))
for sock in sockets:
    sock.close()
PY
)
app_pid=""
fixture_pid=""
cleanup() {
    if [ -n "$app_pid" ]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; fi
    if [ -n "$fixture_pid" ]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi
    pg_ctl -D "$check_dir/pgdata" -m fast -w stop >/dev/null 2>&1 || true
    printf 'Verification logs: %s\n' "$check_dir"
}
trap cleanup EXIT

initdb -D "$check_dir/pgdata" -A trust --no-locale -E UTF8 > "$check_dir/initdb.log"
pg_ctl -D "$check_dir/pgdata" -l "$check_dir/postgres.log" -o "-h 127.0.0.1 -p $pg_port -k $check_dir" -w start
createdb -h 127.0.0.1 -p "$pg_port" linktracker
export DATABASE_URL="jdbc:postgresql://127.0.0.1:$pg_port/linktracker"
export DATABASE_USER="$(id -un)"
export DATABASE_PASSWORD=local-verification
export SCHEDULER_ENABLED=false
export GITHUB_API_URL="http://127.0.0.1:$fixture_port"
export STACKOVERFLOW_API_URL="$GITHUB_API_URL"
export FIXTURE_URL="$GITHUB_API_URL"
export PORT="$app_port"

APP_MODE=migrate MIGRATIONS_ENABLED=true SPRING_MAIN_WEB_APPLICATION_TYPE=none \
    java -jar "$project_dir/scrapper/target/scrapper.jar" > "$check_dir/migration.log" 2>&1
python3 "$project_dir/scripts/api_fixture.py" "$fixture_port" > "$check_dir/fixture.log" 2>&1 &
fixture_pid=$!
MIGRATIONS_ENABLED=false java -jar "$project_dir/scrapper/target/scrapper.jar" > "$check_dir/app.log" 2>&1 &
app_pid=$!
python3 - "$app_port" <<'PY'
import sys, time, urllib.request
url = 'http://127.0.0.1:' + sys.argv[1] + '/actuator/health'
for _ in range(90):
    try:
        urllib.request.urlopen(url, timeout=1).close()
        break
    except OSError:
        time.sleep(1)
else:
    raise SystemExit('Application did not become ready; inspect app.log')
PY
python3 "$project_dir/scripts/check_api.py" "http://127.0.0.1:$app_port"
if [ "${VERIFY_KEEP_RUNNING:-false}" = "true" ]; then
    printf 'UI verification URL: http://127.0.0.1:%s\n' "$app_port"
    wait "$app_pid"
fi
