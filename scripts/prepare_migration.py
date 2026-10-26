#!/usr/bin/env python3
"""Prepare a private migration ZIP locally. Does not publish or contact ECS."""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import sys
import types
import zipfile
import hoj_backup as hb
import restore_dev
from migration_common import data_snapshot, verify_data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', args.version):
        raise RuntimeError('Use a new vMAJOR.MINOR.PATCH version.')
    repo = Path(__file__).resolve().parents[1]
    folder = Path(args.output).resolve()
    if folder.exists():
        raise RuntimeError('Preparation directory already exists; keep the previous package and use a new directory.')
    docker = hb.Docker()
    restore_dev.local_windows_only(docker)
    for name in ('hoj-springboot', 'hoj-vue'):
        if hb.run(['git', '-C', repo, 'status', '--porcelain', '--', name]).stdout.strip():
            raise RuntimeError('Commit application changes before preparing a release.')
    app_images = {}
    for service, local in (('hoj-backend', 'backend'), ('hoj-frontend', 'frontend'), ('hoj-judgeserver', 'judgeserver')):
        image = docker.json('image', 'inspect', 'hoj-local/' + local + ':dev')[0]
        revision = image['Config'].get('Labels', {}).get('org.opencontainers.image.revision', '')
        if not re.fullmatch(r'[0-9a-f]{7,40}', revision):
            raise RuntimeError('Application image is not a clean source build: ' + service)
        directory = 'hoj-vue' if local == 'frontend' else 'hoj-springboot'
        trees = [hb.run(['git', '-C', repo, 'rev-parse', rev + ':' + directory]).stdout.strip()
                 for rev in (revision, 'HEAD')]
        if trees[0] != trees[1]:
            raise RuntimeError('Rebuild current application code before preparing: ' + service)
        app_images[service] = {'Id': image['Id'], 'Signature': hb.signature(image),
                               'SourceRevision': revision, 'ApplicationTree': trees[0]}
    baseline = data_snapshot(docker)
    folder.mkdir(mode=0o700, parents=True)
    hb.backup(docker, types.SimpleNamespace(repo=str(repo), output=str(folder / 'data'), check_only=False))
    archives = list((folder / 'data').glob('hoj-backup-*.tar'))
    if len(archives) != 1:
        raise RuntimeError('Expected one completed backup.')
    archive = archives[0]
    verify_data(baseline, data_snapshot(docker))
    request = {'Schema': 1, 'Version': args.version, 'CreatedAt': datetime.datetime.now().astimezone().isoformat(),
               'Archive': archive.name, 'BackupSHA256': hb.file_hash(archive),
               'ExpectedData': baseline, 'ApplicationImages': app_images}
    request_file = folder / 'migration.json'
    hb.write_json(request_file, request)
    bundle = folder / ('hoj-migration-' + args.version + '.zip')
    with zipfile.ZipFile(bundle, 'x', compression=zipfile.ZIP_DEFLATED) as package:
        for path in (request_file, archive, Path(str(archive) + '.sha256')):
            package.write(path, arcname=path.name)
    os.chmod(bundle, 0o600)
    digest = hb.file_hash(bundle)
    Path(str(bundle) + '.sha256').write_text(digest + '  ' + bundle.name + '\n', encoding='ascii')
    print('MIGRATION PACKAGE READY: ' + str(bundle))
    print('SHA256: ' + digest)
    print('PRIVATE DATA: upload only to your ECS private directory, never GitHub or a public download link.')
    print('Nothing has been published or uploaded. Existing local services are running.')


if __name__ == '__main__':
    os.umask(0o077)
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print('PREPARATION FAILED: ' + str(error), file=sys.stderr)
        sys.exit(1)
