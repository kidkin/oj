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


def run(argv, capture=True, check=True):
    result = subprocess.run([str(x) for x in argv], text=True, encoding="utf-8",
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None)
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


def backup(docker, args):
    repo = Path(args.repo).resolve()
    env = repo / "deploy/.env"
    if not env.is_file():
        raise RuntimeError("deploy/.env is missing; do not generate new credentials for existing data.")
    files = [repo / "deploy/compose.dev.yml"]
    mode = next((line.split("=", 1)[1].strip() for line in env.read_text().splitlines()
                 if line.startswith("HOJ_DEPLOYMENT_MODE=")), "source")
    if mode == "prebuilt":
        files.append(repo / "deploy/compose.prebuilt.yml")
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
                                        "internal": True, "ipam": {"config": [{"subnet": str(network)}]}}}
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
    return result


def local_image(docker, record, service):
    alias = {"hoj-backend": "hoj-local/backend:dev", "hoj-frontend": "hoj-local/frontend:dev",
             "hoj-judgeserver": "hoj-local/judgeserver:dev"}.get(service)
    for candidate in [record["Id"], record["Reference"], alias]:
        if candidate and docker.exists("image", candidate):
            image = docker.json("image", "inspect", candidate)[0]
            if signature(image) == record["Signature"]:
                return image["Id"]
    print("Pulling exact backup image for " + service, flush=True)
    docker.call("pull", "--platform", "linux/amd64", record["Reference"], capture=False)
    image = docker.json("image", "inspect", record["Reference"])[0]
    if signature(image) != record["Signature"]:
        raise RuntimeError("Image configuration/layers differ from backup: " + service)
    return image["Id"]


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
        if any(docker.exists("volume", name) for name in names) or docker.exists("network", args.project + "_hoj-network"):
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
            docker.compose([compose_file], "up", "--detach", "--no-build", "--pull", "never", "--wait",
                           "--wait-timeout", "360", project=args.project, capture=False)
            print("RESTORE READY: http://127.0.0.1:%d" % args.port, flush=True)
        else:
            print("Services have not been started. Use --start on a fresh restore to start automatically.")


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
    args = parser.parse_args()
    docker = Docker()
    if args.action == "backup":
        backup(docker, args)
    else:
        restore(docker, args)


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    try:
        main()
    except (RuntimeError, OSError, ValueError, tarfile.TarError, KeyError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        sys.exit(1)
