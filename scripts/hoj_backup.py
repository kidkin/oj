#!/usr/bin/env python3
"""Cold backup of this HOJ stack; restore only into a new, isolated project.

Python 3.8+, Docker CLI and Compose 2.20+. No third-party Python packages.
Backup packages contain service credentials and must be kept private.
"""
import argparse
import copy
import datetime
import hashlib
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile


SCHEMA = 1
REQUIRED = {"hoj-mysql", "hoj-redis", "hoj-nacos", "hoj-backend",
            "hoj-frontend", "hoj-judgeserver", "hoj-mysql-checker"}
HOST_KEYS = {"MYSQL_SERVICE_HOST", "MYSQL_HOST", "MYSQL_PUBLIC_HOST",
             "REDIS_HOST", "NACOS_URL", "BACKEND_SERVER_HOST", "JUDGE_SERVER_IP"}


def run(argv, capture=True, check=True, stdin=None):
    result = subprocess.run([str(x) for x in argv], text=True, encoding="utf-8",
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None, input=stdin)
    if check and result.returncode:
        # Never print complete command arguments or resolved Compose secrets.
        detail = (result.stderr or "").strip()[-1800:]
        raise RuntimeError("Command failed (exit %s). %s" % (result.returncode, detail))
    return result


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_json(path, data):
    Path(path).write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    os.chmod(path, 0o600)


def file_hash(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def volume_fingerprint(path):
    entries = []
    with tarfile.open(path, "r:gz") as stream:
        for member in stream:
            item = [str(PurePosixPath(member.name)), member.type.decode("ascii"),
                    member.uid, member.gid, member.mode, member.linkname]
            if member.isfile():
                digest = hashlib.sha256()
                with stream.extractfile(member) as content:
                    for block in iter(lambda: content.read(1024 * 1024), b""):
                        digest.update(block)
                item += [member.size, digest.hexdigest()]
            entries.append(item)
    return hashlib.sha256(json.dumps(sorted(entries), ensure_ascii=True).encode()).hexdigest()


def signature(image):
    fields = {key: image.get(key) for key in ("Os", "Architecture", "Config", "RootFS")}
    return hashlib.sha256(json.dumps(fields, sort_keys=True).encode()).hexdigest()


class Docker:
    def __init__(self):
        self.exe = shutil.which("docker")
        if not self.exe and os.name == "nt":
            candidate = Path(os.environ.get("LOCALAPPDATA", "")) / "Programs/DockerDesktop/resources/bin/docker.exe"
            if candidate.is_file():
                self.exe = str(candidate)
        if not self.exe:
            raise RuntimeError("Docker CLI not found.")
        os.environ["PATH"] = str(Path(self.exe).parent) + os.pathsep + os.environ.get("PATH", "")
        self.compose_exe = [self.exe, "compose"]
        if run(self.compose_exe + ["version"], check=False).returncode:
            candidate = Path(self.exe).parent.parent / "cli-plugins/docker-compose.exe"
            if os.name != "nt" or not candidate.is_file():
                raise RuntimeError("Docker Compose plugin not found.")
            self.compose_exe = [str(candidate)]
        if self.call("info", "--format", "{{.OSType}}").strip() != "linux":
            raise RuntimeError("A Linux Docker engine is required.")

    def call(self, *args, **kwargs):
        return run([self.exe] + list(args), **kwargs).stdout

    def json(self, *args):
        return json.loads(self.call(*args))

    def compose(self, files, *args, project=None, env_file=None, capture=True):
        command = list(self.compose_exe)
        if project:
            command += ["--project-name", project]
        if env_file:
            command += ["--env-file", str(env_file)]
        for path in files:
            command += ["--file", str(path)]
        return run(command + list(args), capture=capture).stdout

    def exists(self, kind, name):
        return run([self.exe, kind, "inspect", name], check=False).returncode == 0


def image_record(docker, ref):
    image = docker.json("image", "inspect", ref)[0]
    if image.get("Os") != "linux" or image.get("Architecture") != "amd64":
        raise RuntimeError("Only linux/amd64 backups are supported.")
    repository = ref.split("@")[0]
    if ":" in repository.rsplit("/", 1)[-1]:
        repository = repository.rsplit(":", 1)[0]
    digest_ref = next((x for x in image.get("RepoDigests", [])
                       if x.startswith(repository + "@sha256:")), None)
    if not digest_ref:
        raise RuntimeError("Image has no repository digest: %s. Publish/pull it before backing up." % ref)
    return {"Reference": digest_ref, "Id": image["Id"], "Signature": signature(image)}


def helper(docker, image, volume, folder, archive, restore=False):
    # Host Docker CLI; helper gets no Docker socket and no other business volumes.
    volume_mode = "" if restore else ",readonly"
    backup_mode = ",readonly" if restore else ""
    args = ["run", "--rm", "--network", "none", "--read-only", "--entrypoint", "/bin/sh",
            "--mount", "type=volume,source=%s,target=/volume%s" % (volume, volume_mode),
            "--mount", "type=bind,source=%s,target=/backup%s" % (folder, backup_mode), image,
            "-c", 'exec tar %s /backup/"$1" -C /volume .' % ("-xzpf" if restore else "-czf"),
            "hoj-backup", archive]
    docker.call(*args)
    if restore:
        # BusyBox tar restores regular file ownership, but not symlink ownership.
        # MySQL's mysql.sock link commonly belongs to uid/gid 999; restore it
        # without following its absolute target outside this data volume.
        links = []
        with tarfile.open(Path(folder) / archive, "r:gz") as stream:
            for member in stream:
                if member.issym():
                    links += ["%d:%d" % (member.uid, member.gid),
                              "/volume/" + str(PurePosixPath(member.name))]
        if links:
            docker.call("run", "--rm", "--network", "none", "--read-only", "--entrypoint", "/bin/sh",
                        "--mount", "type=volume,source=%s,target=/volume" % volume, image,
                        "-c", 'while [ "$#" -gt 0 ]; do chown -h "$1" "$2" || exit; shift 2; done',
                        "hoj-link-owners", *links)


def backup(docker, args):
    repo = Path(args.repo).resolve()
    env = repo / "deploy/.env"
    if not env.is_file():
        raise RuntimeError("deploy/.env is missing; do not generate new credentials for existing data.")
    files = [repo / "deploy/compose.dev.yml"]
    mode = next((line.split("=", 1)[1].strip() for line in env.read_text(encoding="utf-8-sig").splitlines()
                 if line.startswith("HOJ_DEPLOYMENT_MODE=")), "source")
    if mode == "prebuilt":
        files.append(repo / "deploy/compose.prebuilt.yml")
    if (repo / "deploy/compose.dev-data.json").is_file():
        files.append(repo / "deploy/compose.dev-data.json")
    config = json.loads(docker.compose(files, "config", "--format", "json", env_file=env))
    if set(config["services"]) != REQUIRED:
        raise RuntimeError("Unexpected service layout; this backup tool only supports this repository's HOJ stack.")
    project = config["name"]
    ids = docker.call("ps", "-aq", "--filter", "label=com.docker.compose.project=" + project).split()
    containers = docker.json("inspect", *ids) if ids else []
    running = [c["Config"]["Labels"]["com.docker.compose.service"] for c in containers
               if c["State"].get("Running")]
    if not (REQUIRED - {"hoj-mysql-checker"}).issubset(set(running)):
        raise RuntimeError("All six HOJ services must be running before this backup.")
    records = {}
    mounts = {m.get("Name") for c in containers for m in c.get("Mounts", []) if m["Type"] == "volume"}
    for key, definition in config["volumes"].items():
        name = definition["name"]
        if name not in mounts:
            raise RuntimeError("Configured volume is not mounted by this stack: " + name)
        info = docker.json("volume", "inspect", name)[0]
        if info["Driver"] != "local" or info.get("Options"):
            raise RuntimeError("Only ordinary local Docker volumes are supported.")
        records[key] = {"SourceName": name, "Archive": "volumes/" + key + ".tar.gz"}
    images = {key: image_record(docker, service["image"]) for key, service in config["services"].items()}
    for container in containers:
        service = container["Config"]["Labels"]["com.docker.compose.service"]
        if service in images:
            actual_image = docker.json("image", "inspect", container["Image"])[0]
            if signature(actual_image) != images[service]["Signature"]:
                raise RuntimeError("Configured image differs from the existing container: " + service)
    helper_image = images["hoj-frontend"]["Id"]
    docker.call("run", "--rm", "--network", "none", "--read-only", "--entrypoint", "/bin/sh",
                helper_image, "-c", "command -v tar >/dev/null")
    if getattr(args, "check_only", False):
        print("BACKUP CHECK PASSED: project %s, %d volumes, %d pinned images. No services stopped."
              % (project, len(records), len(images)))
        return
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    archive = output / ("hoj-backup-" + stamp + ".tar")
    if archive.exists():
        raise RuntimeError("Backup destination already exists.")
    print("Source project: %s; volumes: %d" % (project, len(records)), flush=True)
    print("Stopping HOJ for a consistent cold backup. It will be restarted in finally.", flush=True)
    with tempfile.TemporaryDirectory(prefix=".hoj-backup-", dir=str(output)) as temp:
        work = Path(temp)
        (work / "volumes").mkdir()
        try:
            docker.compose(files, "stop", "--timeout", "30", "hoj-frontend", "hoj-backend",
                           "hoj-judgeserver", "hoj-nacos", env_file=env, capture=False)
            docker.compose(files, "stop", "--timeout", "120", "hoj-mysql", "hoj-redis",
                           env_file=env, capture=False)
            for item in docker.json("inspect", *ids):
                service = item["Config"]["Labels"]["com.docker.compose.service"]
                if item["State"].get("Running") or (service in {"hoj-mysql", "hoj-redis"}
                                                       and item["State"].get("ExitCode") != 0):
                    raise RuntimeError("A container did not stop cleanly; no physical database backup will be made.")
            # Snapshot configuration only after all writers have stopped.
            shutil.copy2(env, work / "source.env")
            os.chmod(work / "source.env", 0o600)
            write_json(work / "source.compose.json", config)
            for key, item in records.items():
                print("Backing up " + key, flush=True)
                helper(docker, helper_image, item["SourceName"], work, item["Archive"])
                item["Fingerprint"] = volume_fingerprint(work / item["Archive"])
            checksums = {str(p.relative_to(work)).replace(os.sep, "/"): file_hash(p)
                         for p in work.rglob("*") if p.is_file()}
            metadata = {"Schema": SCHEMA, "CreatedAt": datetime.datetime.now().astimezone().isoformat(),
                        "SourceProject": project, "Method": "stopped-volume-copy",
                        "Images": images, "Volumes": records, "Checksums": checksums}
            write_json(work / "manifest.json", metadata)
            partial = archive.with_suffix(".partial")
            try:
                with tarfile.open(partial, "w") as stream:
                    for path in sorted(work.rglob("*")):
                        if path.is_file():
                            stream.add(path, arcname=path.relative_to(work).as_posix(), recursive=False)
                os.chmod(partial, 0o600)
                partial.replace(archive)
            finally:
                if partial.exists():
                    partial.unlink()
            digest = file_hash(archive)
            checksum_file = Path(str(archive) + ".sha256")
            checksum_file.write_text(digest + "  " + archive.name + "\n", encoding="ascii")
            os.chmod(checksum_file, 0o600)
            print("Backup saved: " + str(archive), flush=True)
            print("SHA256: " + digest, flush=True)
        finally:
            print("Restarting previously running source services from cached images...", flush=True)
            docker.compose(files, "up", "--detach", "--no-build", "--pull", "never", "--wait",
                           "--wait-timeout", "360", *sorted(set(running)), env_file=env, capture=False)
            print("Source services restarted.", flush=True)
    print("BACKUP COMPLETE: " + str(archive), flush=True)


def unpack(archive, folder, expected_sha256=None):
    if expected_sha256 and file_hash(archive) != expected_sha256.lower():
        raise RuntimeError("Backup SHA256 mismatch; restore cancelled.")
    with tarfile.open(archive, "r:") as stream:
        seen = set()
        for member in stream.getmembers():
            path = PurePosixPath(member.name)
            allowed = (member.name in {"manifest.json", "source.env", "source.compose.json"}
                       or re.fullmatch(r"volumes/[a-z0-9_-]+\.tar\.gz", member.name))
            if not member.isfile() or not allowed or path.is_absolute() or ".." in path.parts or member.name in seen:
                raise RuntimeError("Unexpected file in backup package.")
            seen.add(member.name)
            target = folder.joinpath(*path.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with stream.extractfile(member) as source, open(target, "xb") as destination:
                shutil.copyfileobj(source, destination)
            os.chmod(target, 0o600)
    manifest = read_json(folder / "manifest.json")
    if manifest.get("Schema") != SCHEMA or manifest.get("Method") != "stopped-volume-copy":
        raise RuntimeError("Unsupported backup format.")
    expected_files = {"manifest.json", "source.env", "source.compose.json"}
    expected_files.update(item["Archive"] for item in manifest["Volumes"].values())
    if seen != expected_files or set(manifest["Checksums"]) != seen - {"manifest.json"}:
        raise RuntimeError("Backup file list is incomplete.")
    for relative, digest in manifest["Checksums"].items():
        if file_hash(folder / relative) != digest:
            raise RuntimeError("Backup content checksum failed: " + relative)
    for item in manifest["Volumes"].values():
        with tarfile.open(folder / item["Archive"], "r:gz") as stream:
            for member in stream:
                path = PurePosixPath(member.name)
                if path.is_absolute() or ".." in path.parts or member.isdev() or member.isfifo():
                    raise RuntimeError("Unsafe path or device in volume backup.")
        fingerprint = volume_fingerprint(folder / item["Archive"])
        if item.get("Fingerprint", fingerprint) != fingerprint:
            raise RuntimeError("Volume content fingerprint failed.")
        item["Fingerprint"] = fingerprint
    return manifest


def isolated_config(source, manifest, project, subnet, port):
    if not re.fullmatch(r"hoj-restore-[a-z0-9][a-z0-9-]{0,40}", project):
        raise RuntimeError("Restore project must start with hoj-restore- and use lowercase letters/digits/hyphens.")
    network = ipaddress.ip_network(subnet)
    private_ranges = [ipaddress.ip_network(x) for x in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]
    if network.version != 4 or network.prefixlen != 24 or not any(network.subnet_of(x) for x in private_ranges):
        raise RuntimeError("Use a private IPv4 /24 restore subnet.")
    if not (1024 <= port <= 65535):
        raise RuntimeError("Choose a local restore port between 1024 and 65535.")
    result = copy.deepcopy(source)
    if set(result.get("services", {})) != REQUIRED or set(result.get("networks", {})) != {"hoj-network"}:
        raise RuntimeError("Unexpected backup service/network layout.")
    if set(result["volumes"]) != set(manifest["Volumes"]):
        raise RuntimeError("Backup volume list differs from configuration.")
    addresses = {}
    for definition in result["services"].values():
        old = definition["networks"]["hoj-network"]["ipv4_address"]
        addresses[old] = str(network.network_address + int(old.rsplit(".", 1)[1]))

    def replace_addresses(value):
        if not isinstance(value, str):
            return value
        # Replace complete IP tokens, not partial prefixes or secret values.
        return re.sub(r"(?<![\d.])(?:" + "|".join(re.escape(x) for x in addresses) + r")(?![\d.])",
                      lambda match: addresses[match.group()], value)

    result["name"] = project
    result["networks"] = {"hoj-network": {"name": project + "_hoj-network", "driver": "bridge",
                                        "internal": True, "ipam": {"config": [{"subnet": str(network)}]}},
                          "hoj-access": {"name": project + "_hoj-access", "driver": "bridge"}}
    result["volumes"] = {key: {"name": project + "_" + key} for key in result["volumes"]}
    for key, definition in result["services"].items():
        for forbidden in ("container_name", "build", "ports", "env_file", "devices", "volumes_from"):
            definition.pop(forbidden, None)
        definition["restart"] = "no"
        definition["pull_policy"] = "never"
        definition["image"] = manifest["Images"][key]["Reference"]
        for mount in definition.get("volumes", []):
            if mount.get("type") != "volume" or mount.get("source") not in result["volumes"]:
                raise RuntimeError("Restore refuses bind mounts or unlisted business volumes.")
        for host_key in HOST_KEYS:
            if host_key in definition.get("environment", {}):
                definition["environment"][host_key] = replace_addresses(definition["environment"][host_key])
        if "healthcheck" in definition:
            definition["healthcheck"]["test"] = [replace_addresses(x) for x in definition["healthcheck"]["test"]]
        definition["networks"]["hoj-network"]["ipv4_address"] = addresses[definition["networks"]["hoj-network"]["ipv4_address"]]
    result["services"]["hoj-frontend"]["ports"] = [{"target": 80, "published": str(port),
                                                           "host_ip": "127.0.0.1", "protocol": "tcp"}]
    result["services"]["hoj-frontend"]["networks"]["hoj-access"] = {}
    return result


def local_image(docker, record, service):
    alias = {"hoj-backend": "hoj-local/backend:dev", "hoj-frontend": "hoj-local/frontend:dev",
             "hoj-judgeserver": "hoj-local/judgeserver:dev"}.get(service)
    for candidate in [record["Id"], record["Reference"], alias]:
        if candidate and docker.exists("image", candidate):
            image = docker.json("image", "inspect", candidate)[0]
            if signature(image) == record["Signature"]:
                return image["Id"]
    # Loading a Docker archive can change the manifest/index ID while retaining
    # identical configuration and filesystem layers. Check cached signatures.
    cached_ids = sorted(set(docker.call("image", "ls", "--quiet", "--no-trunc").split()))
    if cached_ids:
        for image in docker.json("image", "inspect", *cached_ids):
            if signature(image) == record["Signature"]:
                return image["Id"]
    print("Pulling exact backup image for " + service, flush=True)
    docker.call("pull", "--platform", "linux/amd64", record["Reference"], capture=False)
    image = docker.json("image", "inspect", record["Reference"])[0]
    if signature(image) != record["Signature"]:
        raise RuntimeError("Image configuration/layers differ from backup: " + service)
    return image["Id"]


def rewrite_runtime_addresses(content, addresses):
    pattern = re.compile(r"(?<![\d.])(?:" + "|".join(re.escape(x) for x in addresses) + r")(?![\d.])")
    lines = []
    for line in content.splitlines(keepends=True):
        key = line.partition(":")[0].lower()
        if any(word in key for word in ("password", "secret", "token")):
            lines.append(line)
        else:
            lines.append(pattern.sub(lambda m: addresses[m.group()], line))
    return "".join(lines)


def start_restored(docker, destination):
    destination = Path(destination).resolve()
    compose_file = destination / "compose.restore.json"
    config = read_json(compose_file)
    source = read_json(destination / "source.compose.json")
    project = config["name"]
    if not re.fullmatch(r"hoj-restore-[a-z0-9][a-z0-9-]{0,40}", project):
        raise RuntimeError("Only isolated hoj-restore-* projects can be started by this tool.")
    if not config["networks"]["hoj-network"].get("internal"):
        raise RuntimeError("Restore network must remain internal.")
    # Docker Desktop's published-port forwarding needs an ordinary access
    # network on the frontend. All business services stay on the internal net.
    config["networks"]["hoj-access"] = {"name": project + "_hoj-access", "driver": "bridge"}
    config["services"]["hoj-frontend"]["networks"]["hoj-access"] = {}
    write_json(compose_file, config)
    for key, definition in config["volumes"].items():
        if definition != {"name": project + "_" + key}:
            raise RuntimeError("Restore refuses shared or externally configured data volumes.")
    for service in config["services"].values():
        for mount in service.get("volumes", []):
            if mount.get("type") != "volume" or mount.get("source") not in config["volumes"]:
                raise RuntimeError("Unexpected mount in restore configuration.")
        for port in service.get("ports", []):
            if port.get("host_ip") != "127.0.0.1":
                raise RuntimeError("Restored ports must remain on loopback.")
    addresses = {service["networks"]["hoj-network"]["ipv4_address"]:
                 config["services"][key]["networks"]["hoj-network"]["ipv4_address"]
                 for key, service in source["services"].items()}
    docker.compose([compose_file], "stop", "--timeout", "30", "hoj-frontend", "hoj-backend",
                   "hoj-judgeserver", "hoj-nacos", project=project, capture=False)
    docker.compose([compose_file], "up", "--detach", "--no-build", "--pull", "never", "--no-deps",
                   "--wait", "--wait-timeout", "180", "hoj-mysql", project=project, capture=False)
    container_id = docker.compose([compose_file], "ps", "--quiet", "hoj-mysql", project=project).strip()
    container = docker.json("inspect", container_id)[0]
    if container["Config"]["Labels"].get("com.docker.compose.project") != project:
        raise RuntimeError("Unexpected MySQL project; no database changes made.")
    mysql_volumes = [m.get("Name") for m in container["Mounts"] if m["Type"] == "volume"]
    if mysql_volumes != [project + "_hoj-mysql-data"]:
        raise RuntimeError("Unexpected MySQL data volume; no database changes made.")

    def query(sql):
        # SQL and the existing container password never appear in host argv/logs.
        result = run([docker.exe, "exec", "-i", container_id, "/bin/sh", "-c",
                      'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --batch --skip-column-names'],
                     stdin=sql, check=False)
        if result.returncode:
            raise RuntimeError("Unable to rebind isolated database configuration; SQL details withheld.")
        return result.stdout

    updates = []
    changed = 0
    for row in query("SELECT id,HEX(content) FROM nacos.config_info;\n").splitlines():
        config_id, content_hex = row.split("\t", 1)
        if not config_id.isdecimal():
            raise RuntimeError("Unexpected Nacos configuration identifier.")
        content = bytes.fromhex(content_hex).decode("utf-8")
        revised = rewrite_runtime_addresses(content, addresses)
        if revised != content:
            value = "CONVERT(0x%s USING utf8mb4)" % revised.encode("utf-8").hex()
            updates.append("UPDATE nacos.config_info SET content=%s,md5=MD5(%s),gmt_modified=NOW() WHERE id=%s;"
                           % (value, value, config_id))
            changed += 1
    # Stale worker registrations are operational metadata, not submissions.
    # Rewrite their addresses so the restarted worker replaces its own old row.
    for old, new in addresses.items():
        old_hex, new_hex = old.encode().hex(), new.encode().hex()
        updates.append("UPDATE hoj.judge_server SET ip=CONVERT(0x%s USING utf8mb4),"
                       "url=REPLACE(url,CONVERT(0x%s USING utf8mb4),CONVERT(0x%s USING utf8mb4)) "
                       "WHERE ip=CONVERT(0x%s USING utf8mb4);" % (new_hex, old_hex, new_hex, old_hex))
    query("START TRANSACTION;\n" + "\n".join(updates) + "\nCOMMIT;\n")
    write_json(destination / "runtime-rebind.json", {"Project": project, "NacosRowsChanged": changed,
                                                     "AddressMap": addresses})
    print("Rebound isolated Nacos configuration rows: %d" % changed, flush=True)
    docker.compose([compose_file], "up", "--detach", "--no-build", "--pull", "never", "--wait",
                   "--wait-timeout", "360", project=project, capture=False)
    port = config["services"]["hoj-frontend"]["ports"][0]["published"]
    print("RESTORE READY: http://127.0.0.1:%s" % port, flush=True)


def restore(docker, args):
    archive = Path(args.archive).resolve()
    destination = Path(args.destination).resolve()
    if destination.exists():
        raise RuntimeError("Restore directory already exists; choose a new empty destination.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    expected_sha256 = args.sha256
    sidecar = Path(str(archive) + ".sha256")
    if not expected_sha256 and sidecar.is_file():
        expected_sha256 = sidecar.read_text(encoding="ascii").split()[0]
        if not re.fullmatch(r"[0-9a-fA-F]{64}", expected_sha256):
            raise RuntimeError("Invalid backup checksum file.")
    with tempfile.TemporaryDirectory(prefix=".hoj-verify-", dir=str(destination.parent)) as temp:
        work = Path(temp)
        manifest = unpack(archive, work, expected_sha256)
        config = isolated_config(read_json(work / "source.compose.json"), manifest, args.project, args.subnet, args.port)
        names = [v["name"] for v in config["volumes"].values()]
        if any(docker.exists("volume", name) for name in names) or any(
                docker.exists("network", definition["name"]) for definition in config["networks"].values()):
            raise RuntimeError("Restore project already has Docker resources; no existing volumes will be overwritten.")
        if docker.call("ps", "-aq", "--filter", "label=com.docker.compose.project=" + args.project).strip():
            raise RuntimeError("Restore project already has containers.")
        network_ids = docker.call("network", "ls", "-q").split()
        wanted = ipaddress.ip_network(args.subnet)
        for network in docker.json("network", "inspect", *network_ids) if network_ids else []:
            for pool in network.get("IPAM", {}).get("Config", []) or []:
                if pool.get("Subnet") and wanted.overlaps(ipaddress.ip_network(pool["Subnet"])):
                    raise RuntimeError("Restore subnet overlaps existing Docker network: " + network["Name"])
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", args.port))
        for key in config["services"]:
            config["services"][key]["image"] = local_image(docker, manifest["Images"][key], key)
        destination.mkdir(mode=0o700)
        shutil.copy2(work / "source.env", destination / "source.env")
        os.chmod(destination / "source.env", 0o600)
        shutil.copy2(work / "source.compose.json", destination / "source.compose.json")
        os.chmod(destination / "source.compose.json", 0o600)
        compose_file = destination / "compose.restore.json"
        write_json(compose_file, config)
        write_json(destination / "manifest.json", manifest)
        docker.compose([compose_file], "config", "--quiet", project=args.project)
        helper_image = config["services"]["hoj-frontend"]["image"]
        (work / "verify").mkdir()
        for key, definition in config["volumes"].items():
            print("Restoring into new volume " + definition["name"], flush=True)
            docker.call("volume", "create", "--label", "com.docker.compose.project=" + args.project,
                        "--label", "com.docker.compose.volume=" + key, definition["name"])
            helper(docker, helper_image, definition["name"], work, manifest["Volumes"][key]["Archive"], restore=True)
            verification = "verify/" + key + ".tar.gz"
            helper(docker, helper_image, definition["name"], work, verification)
            if volume_fingerprint(work / verification) != manifest["Volumes"][key]["Fingerprint"]:
                raise RuntimeError("Restored file contents/ownership/permissions differ: " + key)
            (work / verification).unlink()
        print("RESTORE DATA COMPLETE. Isolated configuration: " + str(compose_file), flush=True)
        if args.start:
            start_restored(docker, destination)
        else:
            print("Services have not been started. Run the start action with --directory to rebind and start this copy.")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    create = actions.add_parser("backup", help="Stop source, copy all volumes, then restart source")
    create.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent))
    create.add_argument("--output", required=True)
    create.add_argument("--check-only", action="store_true", help="Validate the running stack without stopping it")
    recover = actions.add_parser("restore", help="Restore only into NEW hoj-restore-* resources")
    recover.add_argument("archive")
    recover.add_argument("--destination", required=True)
    recover.add_argument("--project", required=True)
    recover.add_argument("--subnet", default="172.30.240.0/24")
    recover.add_argument("--port", type=int, default=18082)
    recover.add_argument("--sha256")
    recover.add_argument("--start", action="store_true")
    start = actions.add_parser("start", help="Rebind stored service addresses and start a completed isolated restore")
    start.add_argument("--directory", required=True)
    args = parser.parse_args()
    docker = Docker()
    if args.action == "backup":
        backup(docker, args)
    elif args.action == "restore":
        restore(docker, args)
    else:
        start_restored(docker, args.directory)


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    try:
        main()
    except (RuntimeError, OSError, ValueError, tarfile.TarError, KeyError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        sys.exit(1)
