#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
deploy_dir="$repo_dir/deploy"
action=${1:-start}
case "$action" in start|build|stop|status) ;; *) echo 'Usage: sh scripts/dev.sh [start|build|stop|status]' >&2; exit 2 ;; esac
docker info --format '{{.OSType}}' | grep -qx linux
docker compose version
if [ ! -f "$deploy_dir/.env" ]; then
  case "$action" in stop|status) echo 'No development environment has been initialized.' >&2; exit 1 ;; esac
  command -v openssl >/dev/null
  umask 077
  cp "$deploy_dir/.env.example" "$deploy_dir/.env"
  for key in MYSQL_ROOT_PASSWORD REDIS_PASSWORD NACOS_PASSWORD JWT_TOKEN_SECRET JUDGE_TOKEN; do
    value=$(openssl rand -hex 24)
    sed -i "s/^$key=.*/$key=$value/" "$deploy_dir/.env"
  done
  echo 'Created deploy/.env with independent random service credentials.'
fi
if command -v git >/dev/null && git -C "$repo_dir" rev-parse HEAD >/dev/null 2>&1; then
  SOURCE_REVISION=$(git -C "$repo_dir" rev-parse --short=12 HEAD)
  if [ -n "$(git -C "$repo_dir" status --porcelain)" ]; then SOURCE_REVISION="$SOURCE_REVISION-dirty"; fi
  export SOURCE_REVISION
fi
compose() { docker compose --project-directory "$deploy_dir" --env-file "$deploy_dir/.env" -f "$deploy_dir/compose.dev.yml" "$@"; }
compose config --quiet
case "$action" in
  start)
    compose build
    compose up --detach --no-build --wait --wait-timeout 360
    echo 'Source-built HOJ is ready: http://127.0.0.1:18081'
    ;;
  build) compose build ;;
  stop) compose stop ;;
  status) compose ps --all ;;
esac
