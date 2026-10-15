#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
deploy_dir="$repo_dir/deploy"
action=${1:-start}
case "$action" in init|start|build|stop|status|logs) ;; *) echo 'Usage: sh scripts/dev.sh [init|start|build|stop|status|logs]' >&2; exit 2 ;; esac
docker_needs_sudo=0
if ! docker info >/dev/null 2>&1; then
  if command -v sudo >/dev/null && sudo docker info >/dev/null; then
    docker_needs_sudo=1
  else
    echo 'Docker Engine is unavailable. On Ubuntu, run bash scripts/install-ubuntu.sh first.' >&2
    exit 1
  fi
fi
docker_run() {
  if [ "$docker_needs_sudo" -eq 1 ]; then sudo docker "$@"; else docker "$@"; fi
}
docker_run info --format '{{.OSType}}' | grep -qx linux
docker_run compose version
if [ ! -f "$deploy_dir/.env" ]; then
  case "$action" in stop|status|logs) echo 'No development environment has been initialized.' >&2; exit 1 ;; esac
  if docker_run volume inspect "${COMPOSE_PROJECT_NAME:-hoj-dev}_hoj-mysql-data" >/dev/null 2>&1; then
    echo 'Existing HOJ database volume found, but deploy/.env is missing. Restore its original .env before starting.' >&2
    exit 1
  fi
  command -v openssl >/dev/null
  umask 077
  env_tmp=$(mktemp "$deploy_dir/.env.tmp.XXXXXX")
  trap 'rm -f -- "$env_tmp"' EXIT HUP INT TERM
  cp "$deploy_dir/.env.example" "$env_tmp"
  for key in MYSQL_ROOT_PASSWORD REDIS_PASSWORD NACOS_PASSWORD JWT_TOKEN_SECRET JUDGE_TOKEN; do
    value=$(openssl rand -hex 24)
    sed -i "s/^$key=.*/$key=$value/" "$env_tmp"
  done
  chmod 600 "$env_tmp"
  mv -- "$env_tmp" "$deploy_dir/.env"
  trap - EXIT HUP INT TERM
  echo 'Created deploy/.env with independent random service credentials.'
fi
if command -v git >/dev/null && git -C "$repo_dir" rev-parse HEAD >/dev/null 2>&1; then
  SOURCE_REVISION=$(git -C "$repo_dir" rev-parse --short=12 HEAD)
  if [ -n "$(git -C "$repo_dir" status --porcelain)" ]; then SOURCE_REVISION="$SOURCE_REVISION-dirty"; fi
  export SOURCE_REVISION
fi
compose() { docker_run compose --project-directory "$deploy_dir" --env-file "$deploy_dir/.env" -f "$deploy_dir/compose.dev.yml" "$@"; }
compose config --quiet
case "$action" in
  init) echo 'Development configuration is ready.' ;;
  start)
    compose build
    compose up --detach --no-build --wait --wait-timeout 360
    api_tmp=$(mktemp)
    trap 'rm -f -- "$api_tmp"' EXIT HUP INT TERM
    compose exec -T hoj-backend curl -fsS --retry 3 --retry-connrefused --max-time 20 http://hoj-frontend/api/get-website-config > "$api_tmp"
    if ! grep -Eq '"status"[[:space:]]*:[[:space:]]*200' "$api_tmp"; then
      echo 'Frontend/API readiness check failed. Run sh scripts/dev.sh logs.' >&2
      exit 1
    fi
    web_port=${HOJ_WEB_PORT:-$(sed -n 's/^HOJ_WEB_PORT=//p' "$deploy_dir/.env" | head -n 1)}
    echo "Source-built HOJ is ready: http://127.0.0.1:${web_port:-18081}"
    ;;
  build) compose build ;;
  stop) compose stop ;;
  status) compose ps --all ;;
  logs) compose logs --tail 120 ;;
esac
