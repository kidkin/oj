#!/usr/bin/env python3
"""Import an HOJ backup into the CURRENT Windows development project.

Old volumes are kept. A local Compose override switches the development stack
to freshly restored external volumes; source building and port 18081 remain.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import types
import urllib.request

import hoj_backup as hb

SECRET_KEYS = ("MYSQL_ROOT_PASSWORD", "REDIS_PASSWORD", "NACOS_USERNAME",
               "NACOS_PASSWORD", "JWT_TOKEN_SECRET", "JUDGE_TOKEN")
INFRA = ("hoj-mysql", "hoj-mysql-checker", "hoj-nacos", "hoj-redis")
APPS = ("hoj-backend", "hoj-frontend", "hoj-judgeserver")


def local_windows_only(docker):
    if os.name != "nt":
        raise RuntimeError("Run this importer on your Windows development computer, not ECS.")
    endpoint = os.environ.get("DOCKER_HOST")
    if not endpoint:
        context = docker.call("context", "show").strip()
        endpoint = docker.json("context", "inspect", context)[0]["Endpoints"]["docker"]["Host"]
    if not endpoint.startswith("npipe:////./pipe/"):
        raise RuntimeError("Only a LOCAL Windows Docker Desktop engine is allowed.")


def files_for(repo):
    files = [repo / "deploy/compose.dev.yml"]
    override = repo / "deploy/compose.dev-data.json"
    if override.is_file():
        files.append(override)
    return files


def checked_dev_config(docker, repo):
    text = (repo / "deploy/.env").read_text(encoding="utf-8-sig")
    if re.search(r"(?m)^HOJ_DEPLOYMENT_MODE=prebuilt\s*$", text):
        raise RuntimeError("This entry requires a source-mode local development environment.")
    config = json.loads(docker.compose(files_for(repo), "config", "--format", "json",
                                       env_file=repo / "deploy/.env"))
    if config.get("name") != "hoj-dev" or set(config.get("services", {})) != hb.REQUIRED:
        raise RuntimeError("Only this repository's hoj-dev project may be changed.")
    ports = config["services"]["hoj-frontend"]["ports"]
    if len(ports) != 1 or ports[0].get("host_ip") != "127.0.0.1" or str(ports[0]["published"]) != "18081":
        raise RuntimeError("Expected the local development website on 127.0.0.1:18081.")
    return config, text


def plan_config(source, current, manifest, restored, current_env):
    if current.get("name") != "hoj-dev":
        raise RuntimeError("Target must be hoj-dev.")
    if set(source["services"]) != hb.REQUIRED or set(current["services"]) != hb.REQUIRED:
        raise RuntimeError("Unexpected service layout.")
    for key in hb.REQUIRED:
        source_ip = source["services"][key]["networks"]["hoj-network"]["ipv4_address"]
        current_ip = current["services"][key]["networks"]["hoj-network"]["ipv4_address"]
        if source_ip != current_ip:
            raise RuntimeError("Source and development service addresses differ; use isolated restore instead.")
    if set(current["volumes"]) != set(restored["volumes"]):
        raise RuntimeError("Volume lists differ.")
    project = restored["name"]
    if not project.startswith(("hoj-restore-devimport-", "hoj-restore-serverimport-")):
        raise RuntimeError("Expected a new development or server import snapshot.")
    old_names = {value["name"] for value in current["volumes"].values()}
    volumes = {}
    for key, value in restored["volumes"].items():
        name = value["name"]
        if name != project + "_" + key or name in old_names:
            raise RuntimeError("Import volumes overlap existing development data.")
        volumes[key] = {"name": name, "external": True}
    backend_env = source["services"]["hoj-backend"]["environment"]
    for key in SECRET_KEYS:
        value = str(backend_env[key])
        if not re.fullmatch(r"[a-zA-Z0-9_@./:+-]+", value):
            raise RuntimeError("Credential characters require manual dotenv handling; no development changes made.")
        if re.search(r"(?m)^" + key + r"=", current_env):
            current_env = re.sub(r"(?m)^" + key + r"=.*$", key + "=" + value, current_env)
        else:
            current_env += "\n" + key + "=" + value + "\n"
    override = {"services": {key: {"image": manifest["Images"][key]["Reference"]} for key in INFRA},
                "volumes": volumes}
    return override, current_env


def compatible_application(repo, service, current, expected):
    config_a, config_b = json.loads(json.dumps(current["Config"])), json.loads(json.dumps(expected["Config"]))
    revision_a = config_a.get("Labels", {}).pop("org.opencontainers.image.revision", "")
    revision_b = config_b.get("Labels", {}).pop("org.opencontainers.image.revision", "")
    if config_a != config_b:
        return False
    if current["RootFS"] == expected["RootFS"]:
        return True
    # Rebuilding unchanged source can change artifact timestamps. The frontend
    # also has a cleanup layer before COPY; its contents can encode timestamps
    # even when the final dist layer is identical. Never ignore a base layer.
    layers_a, layers_b = current["RootFS"]["Layers"], expected["RootFS"]["Layers"]
    if len(layers_a) != len(layers_b) or len(layers_a) < 2:
        return False
    same_base = layers_a[:-1] == layers_b[:-1]
    frontend_cleanup_only = (service == "hoj-frontend" and
                             layers_a[:-2] == layers_b[:-2] and
                             layers_a[-1] == layers_b[-1])
    if not (same_base or frontend_cleanup_only):
        return False
    if not all(re.fullmatch(r"[0-9a-f]{7,40}", value) for value in (revision_a, revision_b)):
        return False
    git = shutil.which("git")
    if not git:
        candidate = Path(os.environ.get("LOCALAPPDATA", "")) / "Programs/Git/cmd/git.exe"
        if candidate.is_file():
            git = str(candidate)
    if not git:
        return False
    app_dir = "hoj-vue" if service == "hoj-frontend" else "hoj-springboot"
    dockerfile = "deploy/Dockerfile.frontend" if service == "hoj-frontend" else "deploy/Dockerfile.java"
    for path in (app_dir, dockerfile):
        trees = [hb.run([git, "-C", repo, "rev-parse", revision + ":" + path], check=False)
                 for revision in (revision_a, revision_b)]
        if not all(result.returncode == 0 for result in trees):
            return False
        if trees[0].stdout.strip() == trees[1].stdout.strip():
            continue
        if path != "deploy/Dockerfile.frontend" or not frontend_cleanup_only:
            return False
        # The release introduced an overridable frontend registry, retaining
        # the same pinned default runtime. Compare after expanding ONLY that
        # declaration; all base layers and compiled dist already match above.
        texts = [hb.run([git, "-C", repo, "show", revision + ":" + path], check=False)
                 for revision in (revision_a, revision_b)]
        if not all(result.returncode == 0 for result in texts):
            return False
        def expanded(text):
            match = re.search(r"(?m)^ARG FRONTEND_RUNTIME_IMAGE=(\S+@sha256:[0-9a-f]{64})\n", text)
            if match:
                text = text.replace(match.group(0), "", 1)
                text = text.replace("FROM ${FRONTEND_RUNTIME_IMAGE}", "FROM " + match.group(1))
            return text
        if expanded(texts[0].stdout) != expanded(texts[1].stdout):
            return False
    return True


def stop(docker, files, env):
    docker.compose(files, "stop", "--timeout", "30", "hoj-frontend", "hoj-backend",
                   "hoj-judgeserver", "hoj-nacos", env_file=env, capture=False)
    docker.compose(files, "stop", "--timeout", "120", "hoj-mysql", "hoj-redis", env_file=env, capture=False)


def start(docker, repo):
    docker.compose(files_for(repo), "up", "--detach", "--no-build", "--pull", "never",
                   "--wait", "--wait-timeout", "360", env_file=repo / "deploy/.env", capture=False)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open("http://127.0.0.1:18081/api/get-website-config", timeout=25) as response:
        if json.load(response)["status"] != 200:
            raise RuntimeError("Development API is not ready.")


def restore_original_files(repo, folder, previous_override):
    shutil.copy2(folder / "previous.env", repo / "deploy/.env")
    target = repo / "deploy/compose.dev-data.json"
    if previous_override:
        shutil.copy2(folder / "previous-override.json", target)
    elif target.exists():
        target.unlink()


def pinned_local_reference(docker, record, service):
    image_id = hb.local_image(docker, record, service)
    image = docker.json("image", "inspect", image_id)[0]
    repository = record["Reference"].split("@")[0]
    refs = image.get("RepoDigests", [])
    reference = next((value for value in refs if value.startswith(repository + "@sha256:")), None)
    if not reference:
        raise RuntimeError("Verified runtime has no repository digest: " + service)
    # A bare image ID would start successfully, but prevent the NEXT backup
    # from recording a portable repository digest. Keep a cached digest ref.
    docker.json("image", "inspect", reference)
    return reference


def import_dev(docker, args):
    repo = Path(args.repo).resolve()
    current, current_env = checked_dev_config(docker, repo)
    archive = Path(args.archive).resolve()
    expected = Path(str(archive) + ".sha256")
    expected_hash = expected.read_text(encoding="ascii").split()[0] if expected.is_file() else None
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    project = "hoj-restore-devimport-" + stamp
    folder = repo / "backups" / ("dev-import-" + stamp)
    with tempfile.TemporaryDirectory(prefix=".dev-import-check-", dir=repo / "logs") as temp:
        manifest = hb.unpack(archive, Path(temp), expected_hash)
        source = hb.read_json(Path(temp) / "source.compose.json")
        restored = hb.isolated_config(source, manifest, project, "172.30.242.0/24", 18084)
        override, revised_env = plan_config(source, current, manifest, restored, current_env)
        for key in APPS:
            image = docker.json("image", "inspect", current["services"][key]["image"])[0]
            backup_image_id = hb.local_image(docker, manifest["Images"][key], key)
            backup_image = docker.json("image", "inspect", backup_image_id)[0]
            if not compatible_application(repo, key, image, backup_image):
                raise RuntimeError("Application version differs from backup: " + key + ". Prepare database migration first.")
        for key in INFRA:
            override["services"][key]["image"] = pinned_local_reference(docker, manifest["Images"][key], key)
        proposed_env = Path(temp) / ".env"
        proposed_env.write_text(revised_env, encoding="utf-8")
        proposed_override = Path(temp) / "override.json"
        hb.write_json(proposed_override, override)
        docker.compose([repo / "deploy/compose.dev.yml", proposed_override], "config", "--quiet", env_file=proposed_env)
    if args.plan_only:
        print("PLAN PASSED: keep original volumes, import %d new volumes, keep source builds and http://127.0.0.1:18081."
              % len(override["volumes"]))
        print("No development services stopped, no development credentials or volume mappings changed.")
        return
    folder.mkdir(mode=0o700, parents=True)
    hb.backup(docker, types.SimpleNamespace(repo=str(repo), output=str(folder / "original-backup"), check_only=False))
    env_file = repo / "deploy/.env"
    override_file = repo / "deploy/compose.dev-data.json"
    previous_override = override_file.is_file()
    shutil.copy2(env_file, folder / "previous.env")
    os.chmod(folder / "previous.env", 0o600)
    if previous_override:
        shutil.copy2(override_file, folder / "previous-override.json")
    state = {"Repo": str(repo), "PreviousOverride": previous_override, "Active": False,
             "Archive": str(archive), "OriginalVolumes": {key: value["name"] for key, value in current["volumes"].items()}}
    state_file = folder / "switch.json"
    hb.write_json(state_file, state)
    try:
        stop(docker, files_for(repo), env_file)
        hb.restore(docker, types.SimpleNamespace(archive=str(archive), destination=str(folder / "restored"),
                   project=project, subnet="172.30.242.0/24", port=18084, sha256=expected_hash, start=False))
        # Use newly verified volumes, with the BACKUP's database runtime and
        # service credentials, under the original development network and code.
        env_file.write_text(revised_env, encoding="utf-8")
        os.chmod(env_file, 0o600)
        hb.write_json(override_file, override)
        start(docker, repo)
        state.update({"Active": True, "EnvHash": hb.file_hash(env_file), "OverrideHash": hb.file_hash(override_file)})
        hb.write_json(state_file, state)
    except BaseException:
        print("Import failed; restoring original development configuration and volumes.", flush=True)
        stop(docker, files_for(repo), env_file)
        restore_original_files(repo, folder, previous_override)
        start(docker, repo)
        raise
    print("DEV RESTORE COMPLETE: http://127.0.0.1:18081", flush=True)
    print("Old development data volumes retained. Switch record: " + str(state_file), flush=True)
    print('To switch back in PowerShell: & "' + sys.executable + '" "' + str(Path(__file__).resolve()) +
          '" undo --state "' + str(state_file) + '"', flush=True)


def undo(docker, args):
    state_file = Path(args.state).resolve()
    state = hb.read_json(state_file)
    if not state.get("Active"):
        raise RuntimeError("This switch record is not active.")
    repo = Path(state["Repo"]).resolve()
    checked_dev_config(docker, repo)
    env_file, override_file = repo / "deploy/.env", repo / "deploy/compose.dev-data.json"
    if hb.file_hash(env_file) != state["EnvHash"] or hb.file_hash(override_file) != state["OverrideHash"]:
        raise RuntimeError("Development configuration changed after import; review it before switching back.")
    stop(docker, files_for(repo), env_file)
    restore_original_files(repo, state_file.parent, state["PreviousOverride"])
    start(docker, repo)
    state["Active"] = False
    hb.write_json(state_file, state)
    print("ORIGINAL DEV DATA RESTORED: http://127.0.0.1:18081")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    recover = commands.add_parser("import")
    recover.add_argument("archive")
    recover.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent))
    recover.add_argument("--plan-only", action="store_true")
    rollback = commands.add_parser("undo")
    rollback.add_argument("--state", required=True)
    args = parser.parse_args()
    docker = hb.Docker()
    local_windows_only(docker)
    if args.action == "import":
        import_dev(docker, args)
    else:
        undo(docker, args)


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        sys.exit(1)
