#!/usr/bin/env python3
"""Read-only verification of a published HOJ release, running images, schema and API."""
import argparse
import json
from pathlib import Path
import re
import sys
import urllib.request

import hoj_backup as hb


APPLICATIONS = {"hoj-backend", "hoj-frontend", "hoj-judgeserver"}


def verify_image(service, container, image, expected):
    references = {expected["Reference"], expected["Reference"].rsplit(":", 1)[0] + "@" + expected["Digest"]}
    if container["Config"]["Image"] not in references:
        raise RuntimeError(service + ": running container is configured for another release")
    if image.get("Os") != "linux" or image.get("Architecture") != "amd64":
        raise RuntimeError(service + ": expected linux/amd64")
    if image["Config"].get("Labels", {}).get("org.opencontainers.image.revision") != expected["SourceRevision"]:
        raise RuntimeError(service + ": running source revision does not match the release")
    if image["RootFS"]["Layers"] != expected["RootFsDiffIds"]:
        raise RuntimeError(service + ": running filesystem layers do not match the release")
    digest_ref = expected["Reference"].rsplit(":", 1)[0] + "@" + expected["Digest"]
    if digest_ref not in image.get("RepoDigests", []):
        raise RuntimeError(service + ": running image lacks the expected registry digest")


def verify_schema(actual, expected):
    # Additional later migrations are not accepted as a verified older release.
    if actual != expected:
        raise RuntimeError("Database migration versions/checksums differ from this release")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", required=True)
    parser.add_argument("--project", default="hoj-dev")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", args.release):
        raise RuntimeError("Invalid release version")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", args.project):
        raise RuntimeError("Invalid Compose project")
    manifest_path = Path(__file__).resolve().parents[1] / "releases" / (args.release + ".json")
    if not manifest_path.is_file():
        raise RuntimeError("Release manifest missing; fetch and check out the matching Git tag first")
    release = hb.read_json(manifest_path)
    if release["Version"] != args.release or set(release["Images"]) != APPLICATIONS:
        raise RuntimeError("Invalid release manifest")
    docker = hb.Docker()
    ids = docker.call("ps", "-aq", "--filter", "label=com.docker.compose.project=" + args.project).split()
    records = docker.json("container", "inspect", *ids) if ids else []
    containers = {}
    for item in records:
        name = item["Config"]["Labels"]["com.docker.compose.service"]
        if name in containers:
            raise RuntimeError("Multiple containers for service " + name)
        containers[name] = item
    if set(containers) != hb.REQUIRED:
        raise RuntimeError("Expected the seven services of the existing HOJ Compose project")
    for name, item in containers.items():
        state = item["State"]
        if name == "hoj-mysql-checker":
            if state.get("Status") != "exited" or state.get("ExitCode") != 0:
                raise RuntimeError("Database initialization checker did not complete successfully")
        else:
            if not state.get("Running"):
                raise RuntimeError(name + ": service is not running")
            # Frontend has no Docker HEALTHCHECK in the existing deployment.
            # Its actual HTTP/API readiness is checked below through its published port.
            if (name != "hoj-frontend" or state.get("Health")) and state.get("Health", {}).get("Status") != "healthy":
                raise RuntimeError(name + ": service is not healthy yet")
    for name in sorted(APPLICATIONS):
        item = containers[name]
        actual = docker.json("image", "inspect", item["Image"])[0]
        verify_image(name, item, actual, release["Images"][name])
        print("PASS IMAGE: %s %s" % (name, release["Images"][name]["SourceRevision"]))
    query = "SELECT version,checksum FROM hoj.edu_schema_history ORDER BY version;"
    result = hb.run([docker.exe, "exec", "-i", containers["hoj-mysql"]["Id"], "sh", "-c",
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names'], stdin=query)
    migrations = dict(line.split("\t") for line in result.stdout.strip().splitlines())
    verify_schema(migrations, release["Migrations"])
    print("PASS SCHEMA: " + ", ".join(sorted(migrations)))
    ports = containers["hoj-frontend"]["NetworkSettings"]["Ports"].get("80/tcp") or []
    if not ports:
        raise RuntimeError("Frontend has no published HTTP port")
    port = int(ports[0]["HostPort"])
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open("http://127.0.0.1:%d/api/get-website-config" % port, timeout=30) as response:
        if response.status != 200 or json.load(response).get("status") != 200:
            raise RuntimeError("Website API readiness check failed")
    print("PASS API: http://127.0.0.1:%d" % port)
    print("RELEASE VERIFIED: " + args.release)
    print("Sign in in your browser to verify existing data and run AC/WA checks.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, TypeError) as error:
        print("RELEASE CHECK FAILED: " + str(error), file=sys.stderr)
        sys.exit(1)
