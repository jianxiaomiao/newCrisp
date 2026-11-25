#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
maven=${NEWCRISP_MAVEN:-mvn}
port=${NEWCRISP_E2E_PORT:-18760}
mysql_image=${NEWCRISP_E2E_MYSQL_IMAGE:-mysql:8.4}

if [[ ! "$port" =~ ^[1-9][0-9]{0,4}$ ]] || (( port > 65535 )); then
  echo 'NEWCRISP_E2E_PORT must be a TCP port between 1 and 65535.' >&2
  exit 1
fi

if [[ -n "${DOCKER_HOST:-}" && -z "${DOCKER_CONTEXT:-}" ]]; then
  docker_endpoint=$DOCKER_HOST
else
  docker_endpoint=$(docker context inspect --format '{{.Endpoints.docker.Host}}' "$(docker context show)")
fi
if [[ "$docker_endpoint" != unix://* ]]; then
  echo 'The Playwright fixture requires a local Docker Unix socket.' >&2
  exit 1
fi
docker info --format '{{.ServerVersion}}' >/dev/null

"$maven" -B -DskipTests package

mysql_password=$(openssl rand -hex 24)
container=$(docker create --rm \
  --label com.newcrisp.fixture=playwright \
  --publish 127.0.0.1::3306 \
  --env MYSQL_DATABASE=newcrisp_e2e \
  --env MYSQL_USER=newcrisp_test \
  --env "MYSQL_PASSWORD=$mysql_password" \
  --env "MYSQL_ROOT_PASSWORD=$(openssl rand -hex 24)" \
  "$mysql_image")
cleanup() {
  local test_status=$?
  trap - EXIT
  if ! docker rm --force --volumes "$container" >/dev/null 2>&1; then
    echo "Could not remove the MySQL fixture automatically: $container" >&2
    if (( test_status == 0 )); then
      test_status=1
    fi
  fi
  exit "$test_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker start "$container" >/dev/null

deadline=$((SECONDS + 120))
until docker exec "$container" sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP --host=127.0.0.1 --connect-timeout=2 --user="$MYSQL_USER" "$MYSQL_DATABASE" --execute="SELECT 1"' >/dev/null 2>&1; do
  if (( SECONDS >= deadline )); then
    echo 'The isolated MySQL fixture did not become ready.' >&2
    exit 1
  fi
  sleep 1
done

mysql_address=$(docker port "$container" 3306/tcp)
if [[ ! "$mysql_address" =~ ^127\.0\.0\.1:[0-9]+$ ]]; then
  echo 'Expected the MySQL fixture to publish on one loopback port.' >&2
  exit 1
fi

unset SPRING_APPLICATION_JSON SPRING_CONFIG_IMPORT SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_PROFILES_ACTIVE
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
export SPRING_CONFIG_LOCATION=classpath:/application.yml
export SPRING_DATASOURCE_URL="jdbc:mysql://$mysql_address/newcrisp_e2e?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
export SPRING_DATASOURCE_USERNAME=newcrisp_test
export SPRING_DATASOURCE_PASSWORD="$mysql_password"
export SPRING_FLYWAY_ENABLED=true
export SPRING_FLYWAY_URL="$SPRING_DATASOURCE_URL"
export SPRING_FLYWAY_USER="$SPRING_DATASOURCE_USERNAME"
export SPRING_FLYWAY_PASSWORD="$SPRING_DATASOURCE_PASSWORD"
export NEWCRISP_E2E_MYSQL_CONTAINER="$container"
export NEWCRISP_E2E_PORT="$port"

node node_modules/@playwright/test/cli.js test "$@"
