import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import types
import unittest
import uuid
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / "hoj_backup.py"
SPEC = importlib.util.spec_from_file_location("hoj_backup", MODULE)
backup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup)


def configuration():
    result = {"name": "hoj-dev", "services": {},
              "volumes": {"hoj-mysql-data": {"name": "hoj-dev_hoj-mysql-data"}},
              "networks": {"hoj-network": {"ipam": {"config": [{"subnet": "172.21.0.0/24"}]}}}}
    for number, service in enumerate(sorted(backup.REQUIRED), start=2):
        result["services"][service] = {
            "image": "example/hoj:1", "build": {"context": "/source"},
            "ports": [{"target": 6688, "published": "16689", "host_ip": "127.0.0.1"}],
            "networks": {"hoj-network": {"ipv4_address": "172.21.0.%d" % number}},
            "environment": {"MYSQL_HOST": "172.21.0.3", "NACOS_URL": "172.21.0.4:8848",
                            "MYSQL_ROOT_PASSWORD": "do-not-change-172.21.0.3"},
            "healthcheck": {"test": ["CMD-SHELL", "curl http://172.21.0.4:8848/"]},
            "volumes": [{"type": "volume", "source": "hoj-mysql-data", "target": "/data"}]}
    return result


def metadata():
    return {"Images": {key: {"Reference": "example/hoj@sha256:" + "a" * 64}
                        for key in backup.REQUIRED},
            "Volumes": {"hoj-mysql-data": {"Archive": "volumes/hoj-mysql-data.tar.gz"}}}


class IsolationTests(unittest.TestCase):
    def test_stored_nacos_addresses_change_but_passwords_and_secrets_do_not(self):
        source = 'url: jdbc:mysql://172.21.0.3:3306/hoj\npassword: keep-172.21.0.3\n  host: 172.21.0.2\njwt-token-secret: keep-172.21.0.3\n'
        revised = backup.rewrite_runtime_addresses(source, {"172.21.0.3": "172.30.240.3", "172.21.0.2": "172.30.240.2"})
        self.assertIn('url: jdbc:mysql://172.30.240.3:3306/hoj', revised)
        self.assertIn('  host: 172.30.240.2', revised)
        self.assertIn('password: keep-172.21.0.3', revised)
        self.assertIn('jwt-token-secret: keep-172.21.0.3', revised)

    def test_restore_has_new_volumes_network_loopback_port_and_unchanged_secrets(self):
        source = configuration()
        before = copy.deepcopy(source)
        config = backup.isolated_config(source, metadata(), "hoj-restore-check", "172.30.240.0/24", 18082)
        self.assertEqual(source, before)
        self.assertTrue(config["networks"]["hoj-network"]["internal"])
        self.assertEqual(config["volumes"]["hoj-mysql-data"]["name"], "hoj-restore-check_hoj-mysql-data")
        for key, service in config["services"].items():
            self.assertNotIn("build", service)
            self.assertEqual(service["environment"]["MYSQL_HOST"], "172.30.240.3")
            self.assertEqual(service["environment"]["NACOS_URL"], "172.30.240.4:8848")
            self.assertEqual(service["environment"]["MYSQL_ROOT_PASSWORD"], "do-not-change-172.21.0.3")
            self.assertIn("172.30.240.4", service["healthcheck"]["test"][1])
            self.assertEqual(service["restart"], "no")
            if key != "hoj-frontend":
                self.assertNotIn("ports", service)
                self.assertNotIn("hoj-access", service["networks"])
        self.assertIn("hoj-access", config["services"]["hoj-frontend"]["networks"])
        ports = config["services"]["hoj-frontend"]["ports"]
        self.assertEqual(ports, [{"target": 80, "published": "18082", "host_ip": "127.0.0.1", "protocol": "tcp"}])

    def test_production_project_names_and_bind_mounts_are_refused(self):
        with self.assertRaises(RuntimeError):
            backup.isolated_config(configuration(), metadata(), "hoj-dev", "172.30.240.0/24", 18082)
        source = configuration()
        source["services"]["hoj-backend"]["volumes"] = [{"type": "bind", "source": "/real/data", "target": "/data"}]
        with self.assertRaises(RuntimeError):
            backup.isolated_config(source, metadata(), "hoj-restore-check", "172.30.240.0/24", 18082)


class ArchiveTests(unittest.TestCase):
    def package(self, directory, corrupt=False):
        work = directory / "input"
        (work / "volumes").mkdir(parents=True)
        (work / "source.env").write_text("MYSQL_ROOT_PASSWORD=private\n")
        backup.write_json(work / "source.compose.json", configuration())
        volume = work / "volumes/hoj-mysql-data.tar.gz"
        with tarfile.open(volume, "w:gz") as stream:
            payload = b"restoration fixture\n"
            entry = tarfile.TarInfo("./important.txt")
            entry.size = len(payload)
            stream.addfile(entry, io.BytesIO(payload))
        info = metadata()
        info.update({"Schema": 1, "Method": "stopped-volume-copy",
                     "Checksums": {p.relative_to(work).as_posix(): backup.file_hash(p)
                                   for p in work.rglob("*") if p.is_file()}})
        backup.write_json(work / "manifest.json", info)
        if corrupt:
            (work / "source.env").write_text("CORRUPTED\n")
        archive = directory / "fixture.tar"
        with tarfile.open(archive, "w") as stream:
            for path in work.rglob("*"):
                if path.is_file():
                    stream.add(path, arcname=path.relative_to(work).as_posix())
        return archive

    def test_valid_package_verified_and_corruption_rejected(self):
        for corrupt in (False, True):
            with tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                archive = self.package(directory, corrupt)
                target = directory / "verified"
                target.mkdir()
                if corrupt:
                    with self.assertRaisesRegex(RuntimeError, "checksum"):
                        backup.unpack(archive, target)
                else:
                    manifest = backup.unpack(archive, target, backup.file_hash(archive))
                    self.assertEqual(manifest["Schema"], 1)

    def test_path_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            archive = directory / "bad.tar"
            with tarfile.open(archive, "w") as stream:
                entry = tarfile.TarInfo("../outside")
                entry.size = 1
                stream.addfile(entry, io.BytesIO(b"x"))
            target = directory / "target"
            target.mkdir()
            with self.assertRaisesRegex(RuntimeError, "Unexpected file"):
                backup.unpack(archive, target)
            self.assertFalse((directory / "outside").exists())

    def test_existing_restore_volume_refused_before_any_write(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            archive = self.package(directory)
            class ExistingDocker:
                def exists(self, kind, name):
                    return kind == "volume"
            args = types.SimpleNamespace(archive=str(archive), destination=str(directory / "restore"),
                                         sha256=None, project="hoj-restore-check", subnet="172.30.240.0/24", port=18082)
            with self.assertRaisesRegex(RuntimeError, "overwritten"):
                backup.restore(ExistingDocker(), args)
            self.assertFalse((directory / "restore").exists())


class RecoveryTests(unittest.TestCase):
    def test_failed_volume_archive_still_restarts_original_services(self):
        config = configuration()
        ids = sorted(backup.REQUIRED)
        class FakeDocker:
            def __init__(self):
                self.commands = []
                self.stopped = False
            def compose(self, files, *args, **kwargs):
                self.commands.append(args)
                if args[0] == "config":
                    return json.dumps(config)
                if args[0] == "stop":
                    self.stopped = True
                return ""
            def call(self, *args, **kwargs):
                return " ".join(ids) if args[0] == "ps" else ""
            def json(self, *args):
                if args[0] == "volume":
                    return [{"Driver": "local", "Options": None}]
                return [{"Image": "fixture", "Config": {"Labels": {"com.docker.compose.service": key}},
                         "State": {"Running": not self.stopped and key != "hoj-mysql-checker", "ExitCode": 0},
                         "Mounts": [{"Type": "volume", "Name": "hoj-dev_hoj-mysql-data"}]}
                        for key in ids]
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            (repo / "deploy").mkdir()
            (repo / "deploy/.env").write_text("HOJ_DEPLOYMENT_MODE=source\n")
            args = types.SimpleNamespace(repo=str(repo), output=str(repo / "backups"))
            docker = FakeDocker()
            with patch.object(backup, "image_record", return_value={"Id": "fixture", "Signature": "fixture"}), \
                 patch.object(backup, "signature", return_value="fixture"), \
                 patch.object(backup, "helper", side_effect=RuntimeError("simulated disk failure")), \
                 contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(RuntimeError, "simulated disk failure"):
                    backup.backup(docker, args)
            self.assertTrue(any(command[0] == "stop" for command in docker.commands))
            self.assertEqual(docker.commands[-1][0], "up")
            self.assertIn("never", docker.commands[-1])
            self.assertFalse(list((repo / "backups").glob("hoj-backup-*.tar")))


@unittest.skipUnless(os.environ.get("HOJ_BACKUP_DOCKER_TEST") == "1", "Opt-in Docker volume integration test")
class DockerVolumeTests(unittest.TestCase):
    def test_bytes_permissions_and_non_root_absolute_symlink_owner_round_trip(self):
        docker = backup.Docker()
        prefix = "hoj-restore-tooltest-" + uuid.uuid4().hex[:8]
        created = []
        image = "hoj-local/frontend:dev"
        try:
            for suffix in ("-source", "-target"):
                name = prefix + suffix
                self.assertFalse(docker.exists("volume", name))
                docker.call("volume", "create", name)
                created.append(name)
            docker.call("run", "--rm", "--network", "none", "--read-only", "--entrypoint", "/bin/sh",
                        "--mount", "type=volume,source=%s,target=/volume" % created[0], image,
                        "-c", "mkdir /volume/sub && printf fixture-data > /volume/sub/a && chmod 600 /volume/sub/a "
                        "&& chown 999:999 /volume/sub/a && ln -s /run/mysql.sock /volume/mysql.sock "
                        "&& chown -h 999:999 /volume/mysql.sock")
            with tempfile.TemporaryDirectory(prefix="docker-volume-test-", dir=MODULE.parent.parent / "logs") as temp:
                folder = Path(temp).resolve()
                backup.helper(docker, image, created[0], folder, "source.tar.gz")
                backup.helper(docker, image, created[1], folder, "source.tar.gz", restore=True)
                backup.helper(docker, image, created[1], folder, "restored.tar.gz")
                self.assertEqual(backup.volume_fingerprint(folder / "source.tar.gz"),
                                 backup.volume_fingerprint(folder / "restored.tar.gz"))
        finally:
            for name in created:
                docker.call("volume", "rm", name)


if __name__ == "__main__":
    unittest.main()
