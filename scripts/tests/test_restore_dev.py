import copy
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import hoj_backup as hb
import restore_dev as dev


def config():
    services = {}
    for index, key in enumerate(sorted(hb.REQUIRED), 2):
        services[key] = {"networks": {"hoj-network": {"ipv4_address": "172.21.0.%d" % index}},
                         "environment": {key: "new-secret" for key in dev.SECRET_KEYS}}
    return {"name": "hoj-dev", "services": services,
            "volumes": {"hoj-mysql-data": {"name": "hoj-dev_hoj-mysql-data"},
                        "hoj-files": {"name": "hoj-dev_hoj-files"}}}


class DevelopmentImportTests(unittest.TestCase):
    def test_infrastructure_keeps_a_digest_reference_for_future_backups(self):
        record = {"Reference": "example/runtime@sha256:" + "a" * 64}
        cached_ref = "example/runtime@sha256:" + "b" * 64
        docker = types.SimpleNamespace(json=lambda *args: [{"RepoDigests": [cached_ref]}])
        with patch.object(hb, "local_image", return_value="sha256:local-image"):
            self.assertEqual(dev.pinned_local_reference(docker, record, "hoj-mysql"), cached_ref)
        docker.json = lambda *args: [{"RepoDigests": []}]
        with patch.object(hb, "local_image", return_value="sha256:local-image"):
            with self.assertRaisesRegex(RuntimeError, "no repository digest"):
                dev.pinned_local_reference(docker, record, "hoj-mysql")

    def test_frontend_cleanup_layer_requires_identical_artifact_base_and_git_inputs(self):
        image = {"Config": {"Labels": {"org.opencontainers.image.revision": "616334a3827a"}},
                 "RootFS": {"Layers": ["base", "cleanup", "same dist"]}}
        rebuilt = copy.deepcopy(image)
        rebuilt["Config"]["Labels"]["org.opencontainers.image.revision"] = "8aaed95589e7"
        rebuilt["RootFS"]["Layers"][1] = "cleanup rebuilt"
        same = types.SimpleNamespace(returncode=0, stdout="identical tree\n")
        different = types.SimpleNamespace(returncode=0, stdout="changed tree\n")
        with patch.object(dev.shutil, "which", return_value="git"), patch.object(hb, "run", return_value=same):
            self.assertTrue(dev.compatible_application(Path('.'), "hoj-frontend", rebuilt, image))
            self.assertFalse(dev.compatible_application(Path('.'), "hoj-backend", rebuilt, image))
            rebuilt["RootFS"]["Layers"][0] = "different base"
            self.assertFalse(dev.compatible_application(Path('.'), "hoj-frontend", rebuilt, image))
        rebuilt["RootFS"]["Layers"][0] = "base"
        with patch.object(dev.shutil, "which", return_value="git"), patch.object(hb, "run", side_effect=[same, different]):
            self.assertFalse(dev.compatible_application(Path('.'), "hoj-frontend", rebuilt, image))

    def test_revision_label_alone_is_not_a_schema_change_but_runtime_change_is(self):
        image = {"Config": {"Labels": {"org.opencontainers.image.revision": "616334a3827a"}, "Cmd": ["run"]},
                 "RootFS": {"Layers": ["base", "application"]}}
        rebuilt = copy.deepcopy(image)
        rebuilt["Config"]["Labels"]["org.opencontainers.image.revision"] = "8aaed95589e7"
        self.assertTrue(dev.compatible_application(Path('.'), "hoj-backend", rebuilt, image))
        rebuilt["Config"]["Cmd"] = ["different"]
        self.assertFalse(dev.compatible_application(Path('.'), "hoj-backend", rebuilt, image))
        rebuilt = copy.deepcopy(image)
        rebuilt["RootFS"]["Layers"][0] = "different base"
        self.assertFalse(dev.compatible_application(Path('.'), "hoj-backend", rebuilt, image))

    def make_plan(self, source=None, target=None):
        source, target = source or config(), target or config()
        restored = {"name": "hoj-restore-devimport-test",
                    "volumes": {key: {"name": "hoj-restore-devimport-test_" + key}
                                for key in target["volumes"]}}
        manifest = {"Images": {key: {"Reference": "example/runtime@sha256:" + "a" * 64}
                               for key in dev.INFRA}}
        env = "HOJ_WEB_PORT=18081\nMAVEN_IMAGE=keep-builder\nMYSQL_ROOT_PASSWORD=old-secret\n"
        return dev.plan_config(source, target, manifest, restored, env)

    def test_uses_new_external_volumes_preserves_source_builds_and_local_settings(self):
        source, target = config(), config()
        before = copy.deepcopy(target)
        overlay, env = self.make_plan(source, target)
        self.assertEqual(target, before)
        self.assertEqual(set(overlay["services"]), set(dev.INFRA))
        self.assertFalse(set(dev.APPS).intersection(overlay["services"]))
        self.assertIn("MAVEN_IMAGE=keep-builder", env)
        self.assertIn("HOJ_WEB_PORT=18081", env)
        self.assertNotIn("old-secret", env)
        self.assertIn("MYSQL_ROOT_PASSWORD=new-secret", env)
        for key, volume in overlay["volumes"].items():
            self.assertTrue(volume["external"])
            self.assertNotEqual(volume["name"], target["volumes"][key]["name"])

    def test_wrong_target_or_different_runtime_addresses_rejected(self):
        target = config()
        target["name"] = "local-hoj"
        with self.assertRaisesRegex(RuntimeError, "hoj-dev"):
            self.make_plan(target=target)
        source = config()
        source["services"]["hoj-mysql"]["networks"]["hoj-network"]["ipv4_address"] = "172.25.0.3"
        with self.assertRaisesRegex(RuntimeError, "addresses differ"):
            self.make_plan(source=source)

    def test_rollback_restores_original_credentials_and_removes_only_new_override(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            (repo / "deploy").mkdir()
            (repo / "saved").mkdir()
            (repo / "saved/previous.env").write_text("MYSQL_ROOT_PASSWORD=original\n")
            (repo / "deploy/.env").write_text("MYSQL_ROOT_PASSWORD=imported\n")
            (repo / "deploy/compose.dev-data.json").write_text("imported")
            keep = repo / "deploy/compose.dev.yml"
            keep.write_text("original project")
            dev.restore_original_files(repo, repo / "saved", False)
            self.assertEqual((repo / "deploy/.env").read_text(), "MYSQL_ROOT_PASSWORD=original\n")
            self.assertFalse((repo / "deploy/compose.dev-data.json").exists())
            self.assertEqual(keep.read_text(), "original project")
            (repo / "saved/previous-override.json").write_text("previous import")
            dev.restore_original_files(repo, repo / "saved", True)
            self.assertEqual((repo / "deploy/compose.dev-data.json").read_text(), "previous import")


if __name__ == "__main__":
    unittest.main()
