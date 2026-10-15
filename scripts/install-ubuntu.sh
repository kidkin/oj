#!/usr/bin/env bash
set -Eeuo pipefail

install_dir="$HOME/oj"
lan=0
port=''
image_source=''
sequential_build=0
usage() {
  echo 'Usage: bash install-ubuntu.sh [--dir PATH] [--lan] [--port PORT] [--image-source aliyun] [--sequential-build]'
  echo 'Ubuntu 22.04/24.04 amd64 VM setup: Docker Engine, Compose, source build and startup.'
  echo '--lan publishes the website on all VM interfaces; default is VM localhost only.'
  echo '--image-source aliyun uses upstream HOJ images on Aliyun and official Maven/Node on Docker Hub.'
  echo '--sequential-build builds services one at a time to reduce peak memory use.'
}
while (($#)); do
  case "$1" in
    --dir) [[ $# -ge 2 ]] || { usage; exit 2; }; install_dir=$2; shift 2 ;;
    --lan) lan=1; shift ;;
    --port) [[ $# -ge 2 ]] || { usage; exit 2; }; port=$2; shift 2 ;;
    --image-source) [[ $# -ge 2 ]] || { usage; exit 2; }; image_source=$2; shift 2 ;;
    --sequential-build) sequential_build=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done
if [[ -n "$image_source" && "$image_source" != aliyun ]]; then
  echo 'Supported image source: aliyun.' >&2; exit 2
fi
if [[ -n "$port" ]] && { [[ ! "$port" =~ ^[0-9]{1,5}$ ]] || ((10#$port < 1 || 10#$port > 65535)); }; then
  echo 'Port must be an integer between 1 and 65535.' >&2; exit 2
fi
if [[ -n "$port" ]]; then port=$((10#$port)); fi
if [[ ! -r /etc/os-release ]]; then echo 'Ubuntu is required.' >&2; exit 1; fi
. /etc/os-release
if [[ ${ID:-} != ubuntu || ! ${VERSION_ID:-} =~ ^(22\.04|24\.04)$ ]]; then
  echo 'Use an Ubuntu 22.04 or 24.04 LTS virtual machine.' >&2; exit 1
fi
if [[ $(dpkg --print-architecture) != amd64 ]]; then
  echo 'This HOJ stack currently requires an amd64/x86_64 Ubuntu VM.' >&2; exit 1
fi
umask 077
log_file="$HOME/hoj-install-$(date +%Y%m%d-%H%M%S)-$$.log"
exec > >(tee -a "$log_file") 2>&1
trap 'code=$?; echo "Installation stopped at line $LINENO (exit $code). Log: $log_file" >&2; exit "$code"' ERR
as_root() { if ((EUID == 0)); then "$@"; else sudo "$@"; fi; }
if ((EUID != 0)); then command -v sudo >/dev/null; sudo -v; fi

echo '[1/5] Preparing Git, curl, certificates and OpenSSL.'
missing=0
for tool in git curl openssl; do command -v "$tool" >/dev/null || missing=1; done
[[ -f /etc/ssl/certs/ca-certificates.crt ]] || missing=1
if ((missing)); then
  as_root apt-get -o Acquire::Retries=3 update
  as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl git openssl
fi

echo '[2/5] Locating the HOJ source repository.'
script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
if [[ -f "$script_dir/../deploy/compose.dev.yml" && -f "$script_dir/dev.sh" ]]; then
  repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
else
  repo_dir=$(realpath -m -- "$install_dir")
  if [[ -f "$repo_dir/deploy/compose.dev.yml" && -f "$repo_dir/scripts/dev.sh" ]]; then
    echo "Reusing existing checkout: $repo_dir (no automatic git pull)."
  elif [[ -e "$repo_dir" ]]; then
    echo "Destination already exists and is not an HOJ checkout: $repo_dir" >&2; exit 1
  else
    git -c http.lowSpeedLimit=1024 -c http.lowSpeedTime=60 clone --depth 1 https://github.com/kidkin/oj.git "$repo_dir"
  fi
fi

echo '[3/5] Preparing Docker Engine and Docker Compose.'
if ! command -v docker >/dev/null; then
  conflicts=()
  for package in docker.io docker-compose docker-compose-v2 docker-doc docker-buildx podman-docker containerd runc; do
    if [[ $(dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null || true) == installed ]]; then conflicts+=("$package"); fi
  done
  if ((${#conflicts[@]})); then
    echo "Conflicting packages found: ${conflicts[*]}" >&2
    echo 'Use a fresh VM or follow https://docs.docker.com/engine/install/ubuntu/ to migrate the existing installation.' >&2
    exit 1
  fi
  docker_script=$(mktemp)
  curl -fsSL --retry 3 --connect-timeout 20 --max-time 180 https://get.docker.com -o "$docker_script"
  as_root sh "$docker_script" --setup-repo
  as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  rm -f -- "$docker_script"
fi
if ! as_root docker info >/dev/null 2>&1; then
  if [[ ! -d /run/systemd/system ]]; then
    echo 'Docker is unavailable and systemd is not running. Run this on an Ubuntu VM, not inside an unprepared container.' >&2; exit 1
  fi
  as_root systemctl enable --now docker
fi
as_root docker info --format '{{.OSType}}' | grep -qx linux
if ! as_root docker compose version; then
  echo 'Docker Compose plugin is missing. Install docker-compose-plugin from the official Docker apt repository.' >&2; exit 1
fi
as_root docker buildx version
compose_version=$(as_root docker compose version --short)
compose_version=${compose_version#v}
compose_major=${compose_version%%.*}
compose_minor=${compose_version#*.}; compose_minor=${compose_minor%%.*}
if [[ ! "$compose_major" =~ ^[0-9]+$ || ! "$compose_minor" =~ ^[0-9]+$ ]] || ((compose_major < 2 || (compose_major == 2 && compose_minor < 20))); then
  echo 'Docker Compose v2.20 or newer is required.' >&2; exit 1
fi

echo '[4/5] Initializing independent application credentials.'
sh "$repo_dir/scripts/dev.sh" init
set_env() {
  if grep -q "^$1=" "$repo_dir/deploy/.env"; then
    sed -i "s|^$1=.*|$1=$2|" "$repo_dir/deploy/.env"
  else
    printf '\n%s=%s\n' "$1" "$2" >> "$repo_dir/deploy/.env"
  fi
}
if ((lan)); then set_env HOJ_WEB_BIND 0.0.0.0; fi
if [[ -n "$port" ]]; then set_env HOJ_WEB_PORT "$port"; fi
if [[ "$image_source" == aliyun ]]; then
  while IFS='=' read -r key value || [[ -n "$key" ]]; do
    [[ -z "$key" || "$key" == \#* ]] && continue
    case "$key" in
      MAVEN_IMAGE|NODE_IMAGE|BACKEND_RUNTIME_IMAGE|FRONTEND_RUNTIME_IMAGE|JUDGESERVER_RUNTIME_IMAGE|MYSQL_IMAGE|MYSQL_CHECKER_IMAGE) ;;
      *) echo "Unexpected image setting: $key" >&2; exit 1 ;;
    esac
    [[ "$value" =~ ^[a-zA-Z0-9_./:@-]+$ ]] || { echo "Invalid image reference for $key" >&2; exit 1; }
    set_env "$key" "$value"
  done < "$repo_dir/deploy/images.aliyun.env"
  echo 'Selected upstream HOJ Aliyun images and official Docker Hub build images.'
fi
if ((sequential_build)); then set_env HOJ_BUILD_SEQUENTIAL true; fi

echo '[5/5] Building the source and starting HOJ. Keep this terminal open.'
sh "$repo_dir/scripts/dev.sh" start
web_port=$(sed -n 's/^HOJ_WEB_PORT=//p' "$repo_dir/deploy/.env" | head -n 1)
echo
echo "Installation complete. Repository: $repo_dir"
echo "Inside Ubuntu: http://127.0.0.1:${web_port:-18081}"
if ((lan)); then
  primary_ip=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") {print $(i+1); exit}}' || true)
  echo "From Windows (if VM networking permits): http://${primary_ip:-VM-IP}:${web_port:-18081}"
fi
echo 'Initial super administrator: root / hoj123456'
echo "Status: sh \"$repo_dir/scripts/dev.sh\" status"
echo "Stop:   sh \"$repo_dir/scripts/dev.sh\" stop"
echo "Installation log: $log_file"
