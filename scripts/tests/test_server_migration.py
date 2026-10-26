import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import hoj_backup as hb
import migration_common as common
import restore_dev
import restore_server as server
from test_restore_dev import config


class MigrationPackageTests(unittest.TestCase):
    def bundle(self, folder, extra=None, corrupt=False):
        content = b'private backup fixture'
        archive = 'hoj-backup-20261026-220000.tar'
        request = {'Schema': 1, 'Version': 'v0.3.1', 'Archive': archive,
                   'BackupSHA256': hashlib.sha256(content).hexdigest(), 'ExpectedData': {}}
        path = folder / 'bundle.zip'
        with zipfile.ZipFile(path, 'w') as package:
            package.writestr('migration.json', json.dumps(request))
            package.writestr(archive, b'wrong' if corrupt else content)
            package.writestr(archive + '.sha256', request['BackupSHA256'])
            if extra:
                package.writestr(extra, b'forbidden')
        return path

    def test_valid_bundle_preserves_private_backup_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp); target = folder / 'target'; target.mkdir()
            request = common.unpack_bundle(self.bundle(folder), target)
            self.assertEqual(hb.file_hash(target / request['Archive']), request['BackupSHA256'])

    def test_extra_and_traversal_members_are_rejected_without_writing(self):
        for extra in ('../outside', 'unexpected.txt'):
            with tempfile.TemporaryDirectory() as temp:
                folder = Path(temp); target = folder / 'target'; target.mkdir()
                with self.assertRaisesRegex(RuntimeError, 'file list'):
                    common.unpack_bundle(self.bundle(folder, extra=extra), target)
                self.assertEqual(list(target.iterdir()), [])
                self.assertFalse((folder / 'outside').exists())

    def test_corrupted_backup_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp); target = folder / 'target'; target.mkdir()
            with self.assertRaisesRegex(RuntimeError, 'checksum'):
                common.unpack_bundle(self.bundle(folder, corrupt=True), target)

    def test_counts_and_primary_keys_must_both_match(self):
        expected = {'user_info': {'Count': 18, 'PrimaryKeysSHA256': 'original'}}
        common.verify_data(expected, copy.deepcopy(expected))
        for actual in ({}, {'user_info': {'Count': 18, 'PrimaryKeysSHA256': 'different'}},
                       {'user_info': {'Count': 17, 'PrimaryKeysSHA256': 'original'}}):
            with self.assertRaisesRegex(RuntimeError, 'identifiers'):
                common.verify_data(expected, actual)


class PublishedBackupResolutionTests(unittest.TestCase):
    def setUp(self):
        self.image = {'Id': 'sha256:cached', 'Os': 'linux', 'Architecture': 'amd64',
                      'Config': {'Labels': {'org.opencontainers.image.revision': '123456789abc'}},
                      'RootFS': {'Layers': ['sha256:base', 'sha256:app']}}
        services = ('hoj-backend', 'hoj-frontend', 'hoj-judgeserver')
        self.release = {'Images': {service: {'Reference': 'registry.example/oj/' + service + ':v0.3.1',
             'Digest': 'sha256:' + 'a' * 64, 'SourceRevision': '123456789abc',
             'RootFsDiffIds': self.image['RootFS']['Layers']} for service in services}}
        self.manifest = {'Images': {service: {'Reference': 'hoj-local/' + service + '@sha256:local',
                                    'Signature': hb.signature(self.image)} for service in services}}
        self.image['RepoDigests'] = [record['Reference'].rsplit(':', 1)[0] + '@' + record['Digest']
                                     for record in self.release['Images'].values()]
        self.docker = Mock()
        self.docker.exists.return_value = True
        self.docker.json.side_effect = lambda *args: [copy.deepcopy(self.image)]

    def test_identical_published_image_replaces_only_development_references(self):
        original = copy.deepcopy(self.manifest)
        result = hb.resolve_release_images(self.docker, self.manifest, self.release)
        self.assertEqual(self.manifest, original)
        for record in result['Images'].values():
            self.assertTrue(record['Reference'].startswith('registry.example/'))
        self.docker.call.assert_not_called()

    def test_changed_source_or_filesystem_or_digest_is_rejected(self):
        for field in ('source', 'filesystem', 'digest'):
            image = copy.deepcopy(self.image)
            if field == 'source': image['Config']['Labels']['org.opencontainers.image.revision'] = 'other'
            elif field == 'filesystem': image['RootFS']['Layers'] += ['other']
            else: image['RepoDigests'] = []
            self.docker.json.side_effect = lambda *args: [image]
            with self.assertRaisesRegex(RuntimeError, 'differs'):
                hb.resolve_release_images(self.docker, self.manifest, self.release)


class ServerAdoptionTests(unittest.TestCase):
    def plan(self, source=None, restored=None):
        source = source or config(); target = config()
        project = 'hoj-restore-serverimport-test'
        restored = restored or {'name': project, 'volumes': {key: {'name': project + '_' + key} for key in target['volumes']}}
        manifest = {'Images': {key: {'Reference': 'example/runtime@sha256:' + 'a' * 64} for key in restore_dev.INFRA}}
        release = {'Images': {key: {'Reference': 'registry.example/oj/' + key + ':v0.3.1',
                                   'Digest': 'sha256:' + 'a' * 64} for key in restore_dev.APPS}}
        return server.plan_import(source, target, manifest, restored,
            'HOJ_WEB_BIND=0.0.0.0\nHOJ_WEB_PORT=80\nMYSQL_ROOT_PASSWORD=old-secret\n', release, 18082)

    def test_new_data_is_private_and_next_update_can_replace_application_images(self):
        env, overlay = self.plan()
        self.assertIn('HOJ_WEB_BIND=127.0.0.1', env)
        self.assertIn('HOJ_WEB_PORT=18082', env)
        self.assertIn('HOJ_DEPLOYMENT_MODE=prebuilt', env)
        self.assertIn('HOJ_BACKEND_IMAGE=registry.example/oj/hoj-backend@sha256:' + 'a' * 64, env)
        self.assertNotIn('old-secret', env)
        self.assertIn('MYSQL_ROOT_PASSWORD=new-secret', env)
        self.assertFalse(set(restore_dev.APPS).intersection(overlay['services']))
        for record in overlay['volumes'].values():
            self.assertTrue(record['external'])
            self.assertTrue(record['name'].startswith('hoj-restore-serverimport-'))

    def test_mismatched_addresses_and_reusing_original_volumes_are_refused(self):
        source = config()
        source['services']['hoj-backend']['networks']['hoj-network']['ipv4_address'] = '172.24.0.5'
        with self.assertRaisesRegex(RuntimeError, 'addresses differ'):
            self.plan(source=source)
        with self.assertRaisesRegex(RuntimeError, 'overlap'):
            self.plan(restored={'name': 'hoj-restore-serverimport-test', 'volumes': config()['volumes']})

    def test_rollback_uses_original_config_and_keeps_business_volumes(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp); repo = folder / 'repo'; (repo / 'deploy').mkdir(parents=True)
            (repo / 'deploy/.env').write_text('new-credentials')
            hb.write_json(repo / 'deploy/compose.dev-data.json', {'volumes': {'new': {}}})
            (folder / 'previous.env').write_text('original-credentials')
            hb.write_json(folder / 'previous.compose.json', {'name': 'hoj-dev'})
            state = {'Repo': str(repo), 'PreviousOverride': False, 'PreviousData': {}, 'Active': True}
            docker = Mock()
            with patch.object(server, 'stop'), patch.object(server, 'start') as start, \
                    patch.object(server, 'data_snapshot', return_value={}):
                server.restore_previous(docker, state, folder)
                start.assert_called_once_with(docker, [folder / 'previous.compose.json'])
            self.assertEqual((repo / 'deploy/.env').read_text(), 'original-credentials')
            self.assertFalse((repo / 'deploy/compose.dev-data.json').exists())
            self.assertFalse(state['Active'])
            docker.call.assert_not_called()

    def test_default_password_never_reaches_public_access_without_user_entry(self):
        with patch.object(server, 'sql', return_value=hashlib.md5(b'hoj123456').hexdigest()), \
                patch.object(server.sys.stdin, 'isatty', return_value=False), patch.object(server, 'Client') as client:
            with self.assertRaisesRegex(RuntimeError, 'interactive terminal'):
                server.reset_default_site_password(Mock(), 18082)
            client.assert_not_called()


if __name__ == '__main__':
    unittest.main()
