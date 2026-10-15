import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_release import verify_image, verify_schema


class ReleaseVerificationTests(unittest.TestCase):
    def setUp(self):
        self.expected = {"Reference": "registry.example/oj/backend:v0.2.0", "Digest": "sha256:" + "a" * 64,
                         "SourceRevision": "123456789abc", "RootFsDiffIds": ["sha256:" + "b" * 64]}
        self.container = {"Config": {"Image": self.expected["Reference"]}}
        self.image = {"Os": "linux", "Architecture": "amd64", "Config": {"Labels": {"org.opencontainers.image.revision": "123456789abc"}},
                      "RootFS": {"Layers": list(self.expected["RootFsDiffIds"])},
                      "RepoDigests": ["registry.example/oj/backend@" + self.expected["Digest"]]}

    def test_published_tag_and_pinned_digest_are_accepted(self):
        verify_image("backend", self.container, self.image, self.expected)
        self.container["Config"]["Image"] = self.image["RepoDigests"][0]
        verify_image("backend", self.container, self.image, self.expected)

    def test_retagging_an_old_running_container_is_rejected(self):
        self.image["Config"]["Labels"]["org.opencontainers.image.revision"] = "old-revision"
        with self.assertRaisesRegex(RuntimeError, "revision"):
            verify_image("backend", self.container, self.image, self.expected)

    def test_modified_layers_with_a_correct_revision_are_rejected(self):
        self.image["RootFS"]["Layers"].append("unexpected layer")
        with self.assertRaisesRegex(RuntimeError, "filesystem"):
            verify_image("backend", self.container, self.image, self.expected)

    def test_other_tag_and_other_registry_digest_are_rejected(self):
        self.container["Config"]["Image"] = "registry.example/oj/backend:v0.1.0"
        with self.assertRaisesRegex(RuntimeError, "another release"):
            verify_image("backend", self.container, self.image, self.expected)
        self.container["Config"]["Image"] = self.expected["Reference"]
        self.image["RepoDigests"] = ["registry.example/oj/backend@wrong"]
        with self.assertRaisesRegex(RuntimeError, "digest"):
            verify_image("backend", self.container, self.image, self.expected)

    def test_missing_changed_or_later_migrations_are_rejected(self):
        expected = {"V001": "old", "V002": "new"}
        verify_schema(dict(expected), expected)
        for actual in ({"V001": "old"}, {"V001": "old", "V002": "changed"}, dict(expected, V003="later")):
            with self.assertRaisesRegex(RuntimeError, "migration"):
                verify_schema(actual, expected)


if __name__ == "__main__":
    unittest.main()
