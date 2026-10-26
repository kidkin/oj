#!/usr/bin/env python3
"""Import a prepared LOCAL data snapshot into the existing Ubuntu HOJ site.

The server's previous volumes are retained, and its configuration and complete
cold backup are saved first. Test on loopback before opening the public port.
"""
import argparse
import datetime
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import types
import hoj_backup as hb
import restore_dev
from migration_common import data_snapshot, sql, unpack_bundle, verify_data
from verify_classes import Client

REPO = Path(__file__).resolve().parents[1]


def server_only(docker):
    if not sys.platform.startswith('linux') or os.geteuid() != 0:
        raise RuntimeError('Run this entry in the Ubuntu ECS root terminal, not Windows.')
    endpoint = os.environ.get('DOCKER_HOST')
    if not endpoint:
        name = docker.call('context', 'show').strip()
        endpoint = docker.json('context', 'inspect', name)[0]['Endpoints']['docker']['Host']
    if not endpoint.startswith('unix://'):
        raise RuntimeError('Only a local Ubuntu Docker engine is allowed.')


def files_for(repo):
    files = [repo / 'deploy/compose.dev.yml']
    env = (repo / 'deploy/.env').read_text(encoding='utf-8-sig')
    if re.search(r'(?m)^HOJ_DEPLOYMENT_MODE=prebuilt\s*$', env):
        files.append(repo / 'deploy/compose.prebuilt.yml')
    if (repo / 'deploy/compose.dev-data.json').is_file():
        files.append(repo / 'deploy/compose.dev-data.json')
    return files


def set_env(text, key, value):
    if '\n' in str(value) or '\r' in str(value):
        raise RuntimeError('Invalid environment value.')
    line = key + '=' + str(value)
    if re.search(r'(?m)^' + re.escape(key) + '=', text):
        return re.sub(r'(?m)^' + re.escape(key) + r'=.*$', lambda _: line, text)
    return text.rstrip() + '\n' + line + '\n'


def write_env(path, text):
    path.write_text(text, encoding='utf-8')
    os.chmod(path, 0o600)


def stop(docker, files, env=None, after_writers=None):
    docker.compose(files, 'stop', '--timeout', '30', 'hoj-frontend', 'hoj-backend',
                   'hoj-judgeserver', 'hoj-nacos', env_file=env, capture=False)
    if after_writers:
        after_writers()
    docker.compose(files, 'stop', '--timeout', '120', 'hoj-mysql', 'hoj-redis', env_file=env, capture=False)


def start(docker, files, env=None):
    docker.compose(files, 'up', '--detach', '--no-build', '--pull', 'never', '--wait',
                   '--wait-timeout', '480', env_file=env, capture=False)


def checked_config(docker, repo):
    env = repo / 'deploy/.env'
    config = json.loads(docker.compose(files_for(repo), 'config', '--format', 'json', env_file=env))
    if config.get('name') != 'hoj-dev' or set(config['services']) != hb.REQUIRED:
        raise RuntimeError('Expected the existing hoj-dev deployment; no other project will be changed.')
    ports = config['services']['hoj-frontend'].get('ports', [])
    if len(ports) != 1 or int(ports[0]['target']) != 80:
        raise RuntimeError('Unexpected website port configuration.')
    return config


def verify_version(repo, version):
    hb.run([sys.executable, repo / 'scripts/verify_release.py', '--release', version], capture=False)


def plan_import(source, current, manifest, restored, env_text, release, staging_port):
    # Shares the already-tested credential and fresh-volume adoption checks
    # with the Windows importer, without its Windows-only orchestration.
    override, revised = restore_dev.plan_config(source, current, manifest, restored, env_text)
    revised = set_env(revised, 'HOJ_DEPLOYMENT_MODE', 'prebuilt')
    for service, key in (('hoj-backend', 'HOJ_BACKEND_IMAGE'), ('hoj-frontend', 'HOJ_FRONTEND_IMAGE'),
                         ('hoj-judgeserver', 'HOJ_JUDGESERVER_IMAGE')):
        record = release['Images'][service]
        revised = set_env(revised, key, record['Reference'].rsplit(':', 1)[0] + '@' + record['Digest'])
    revised = set_env(revised, 'HOJ_WEB_BIND', '127.0.0.1')
    revised = set_env(revised, 'HOJ_WEB_PORT', staging_port)
    return revised, override


def reset_default_site_password(docker, port):
    # Only the imported copy can change here. The old server database and its
    # credentials stay untouched. Invalidate copied root sessions via the
    # existing administrative password-change operation.
    default_md5 = hashlib.md5(b'hoj123456').hexdigest()
    current = sql(docker, 'hoj-dev', "SELECT password FROM user_info WHERE username='root';")
    if current != default_md5:
        return
    if not sys.stdin.isatty():
        raise RuntimeError('The imported website root password is still the default. Run --publish in an interactive terminal to set it before public access.')
    while True:
        password = getpass.getpass('设置网站 root 管理员的新密码（6~20位，不是数据库密码）: ')
        again = getpass.getpass('再次输入新密码: ')
        if (6 <= len(password) <= 20 and password == password.strip()
                and password == again and password != 'hoj123456'):
            break
        print('密码须为6~20位、首尾无空白、两次一致，并与初始密码不同。')
    api = Client('http://127.0.0.1:' + str(port)).login('root', 'hoj123456')
    rows = api.call('/api/admin/user/get-user-list?limit=30&currentPage=1&keyword=root')['records']
    root = next(row for row in rows if row['username'] == 'root')
    if not any(int(role['id']) == 1000 for role in root['roles']):
        raise RuntimeError('Expected the website root administrator.')
    api.call('/api/admin/user/edit-user', 'PUT', {
        'uid': root['uid'], 'username': root['username'], 'realname': root.get('realname'),
        'email': root.get('email'), 'password': password, 'type': 1000, 'status': root['status'],
        'setNewPwd': True, 'titleName': root.get('titleName'), 'titleColor': root.get('titleColor')})
    Client('http://127.0.0.1:' + str(port)).login('root', password)
    print('网站 root 密码已更新，旧 root 登录会话已失效；密码未写入迁移记录。')


def restore_previous(docker, state, folder):
    repo = Path(state['Repo'])
    stop(docker, files_for(repo), repo / 'deploy/.env')
    shutil.copy2(folder / 'previous.env', repo / 'deploy/.env')
    os.chmod(repo / 'deploy/.env', 0o600)
    override = repo / 'deploy/compose.dev-data.json'
    if state['PreviousOverride']:
        shutil.copy2(folder / 'previous-override.json', override)
        os.chmod(override, 0o600)
    elif override.exists():
        override.unlink()
    # Use the exact cached pre-migration images, not mutable tags or a build.
    start(docker, [folder / 'previous.compose.json'])
    verify_data(state['PreviousData'], data_snapshot(docker))
    state['Active'] = False
    state['Status'] = 'previous-restored'
    hb.write_json(folder / 'switch.json', state)
    print('PREVIOUS SERVER RESTORED: original database and files are active.')


def import_server(docker, args):
    repo = Path(args.repo).resolve()
    version = args.release
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', version):
        raise RuntimeError('Invalid release version.')
    release = hb.read_json(repo / 'releases' / (version + '.json'))
    if release.get('Version') != version:
        raise RuntimeError('Fetch the matching published Git release first.')
    current = checked_config(docker, repo)
    previous_data = data_snapshot(docker)
    for record in current['volumes'].values():
        if not docker.exists('volume', record['name']):
            raise RuntimeError('Current server data volume is missing.')
    if shutil.disk_usage(repo).free < 6 * 1024**3:
        raise RuntimeError('Keep at least 6 GiB of free disk space for backups, images and imported volumes.')
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    folder = Path(args.output).resolve() / ('import-' + version + '-' + stamp)
    folder.mkdir(mode=0o700, parents=True)
    incoming = folder / 'incoming'
    incoming.mkdir(mode=0o700)
    request = unpack_bundle(args.bundle, incoming)
    if request['Version'] != version:
        raise RuntimeError('Package and requested release versions differ.')
    archive = incoming / request['Archive']
    # Validate the entire backup and resolve development digests while the old
    # site is still running; registry failures cause no downtime.
    with tempfile.TemporaryDirectory(prefix='.hoj-import-check-', dir=folder) as temporary:
        metadata = hb.unpack(archive, Path(temporary), request['BackupSHA256'])
        hb.resolve_release_images(docker, metadata, release)
        source = hb.read_json(Path(temporary) / 'source.compose.json')
        if set(source['volumes']) != set(current['volumes']):
            raise RuntimeError('Source and target volume layouts differ.')
        for service in hb.REQUIRED:
            if source['services'][service]['networks']['hoj-network']['ipv4_address'] != current['services'][service]['networks']['hoj-network']['ipv4_address']:
                raise RuntimeError('Service addresses differ; do not use direct adoption for this server.')
    hb.backup(docker, types.SimpleNamespace(repo=str(repo), output=str(folder / 'previous-backup'), check_only=False))
    shutil.copy2(repo / 'deploy/.env', folder / 'previous.env')
    os.chmod(folder / 'previous.env', 0o600)
    old_override = repo / 'deploy/compose.dev-data.json'
    if old_override.is_file():
        shutil.copy2(old_override, folder / 'previous-override.json')
        os.chmod(folder / 'previous-override.json', 0o600)
    hb.write_json(folder / 'previous.compose.json', current)
    state = {'Repo': str(repo), 'Version': version, 'PreviousOverride': old_override.is_file(),
             'PreviousData': previous_data, 'ExpectedData': request['ExpectedData'],
             'OriginalVolumes': {key: record['name'] for key, record in current['volumes'].items()},
             'Active': False, 'Status': 'prepared', 'PublicPort': args.port}
    hb.write_json(folder / 'switch.json', state)
    restored = folder / 'restored'
    project = 'hoj-restore-serverimport-' + stamp
    hb.restore(docker, types.SimpleNamespace(archive=str(archive), destination=str(restored), project=project,
               subnet=args.subnet, port=args.staging_port, sha256=request['BackupSHA256'], start=False, release=version))
    manifest = hb.read_json(restored / 'manifest.json')
    revised, override = plan_import(hb.read_json(restored / 'source.compose.json'), current, manifest,
        hb.read_json(restored / 'compose.restore.json'), (repo / 'deploy/.env').read_text(encoding='utf-8-sig'),
        release, args.staging_port)
    proposal_env, proposal_override = folder / 'proposed.env', folder / 'proposed-override.json'
    write_env(proposal_env, revised)
    hb.write_json(proposal_override, override)
    docker.compose([repo / 'deploy/compose.dev.yml', repo / 'deploy/compose.prebuilt.yml', proposal_override],
                   'config', '--quiet', env_file=proposal_env)
    state['EnvHash'] = hb.file_hash(proposal_env)
    state['OverrideHash'] = hb.file_hash(proposal_override)
    state['StagingPort'] = args.staging_port
    state['Status'] = 'switching'
    hb.write_json(folder / 'switch.json', state)
    try:
        def capture_final_previous_data():
            state['PreviousData'] = data_snapshot(docker)
            hb.write_json(folder / 'switch.json', state)
        stop(docker, [folder / 'previous.compose.json'], after_writers=capture_final_previous_data)
        shutil.copy2(proposal_env, repo / 'deploy/.env')
        hb.write_json(old_override, override)
        start(docker, files_for(repo), repo / 'deploy/.env')
        verify_data(request['ExpectedData'], data_snapshot(docker))
        verify_version(repo, version)
        if Client('http://127.0.0.1:' + str(args.staging_port)).call('/api/get-website-config')['register'] is not False:
            raise RuntimeError('Public registration is unexpectedly enabled.')
        state.update({'Active': True, 'Status': 'verified-private'})
        hb.write_json(folder / 'switch.json', state)
        (repo / 'backups').mkdir(mode=0o700, exist_ok=True)
        hb.write_json(repo / 'backups/last-server-import.json', {'StateFile': str(folder / 'switch.json')})
        print('LOCAL DATA RESTORE VERIFIED: all ten volumes and core record identifiers checked.')
        if args.publish:
            publish(docker, types.SimpleNamespace(state=str(folder / 'switch.json')))
    except BaseException:
        print('Import failed; restoring previous server configuration and original data.', flush=True)
        restore_previous(docker, state, folder)
        raise
    print('SWITCH RECORD: ' + str(folder / 'switch.json'))
    print('Old server data and its full backup are retained; no volumes have been deleted.')
    if not args.publish:
        print('PRIVATE ONLY: old public site is stopped. Run publish or undo with the switch record to continue.')


def checked_state(args):
    selected = (hb.read_json(REPO / 'backups/last-server-import.json')['StateFile']
                if getattr(args, 'latest', False) else args.state)
    path = Path(selected).resolve()
    state = hb.read_json(path)
    repo = Path(state['Repo'])
    if not state.get('Active') or state.get('Status') not in ('verified-private', 'published'):
        raise RuntimeError('This switch record is not active.')
    if (hb.file_hash(repo / 'deploy/.env') != state['EnvHash']
            or hb.file_hash(repo / 'deploy/compose.dev-data.json') != state['OverrideHash']):
        raise RuntimeError('Deployment configuration changed after import; review it before switching.')
    return path, state, repo


def publish(docker, args):
    path, state, repo = checked_state(args)
    if state['Status'] != 'verified-private':
        raise RuntimeError('This import is already published.')
    verify_data(state['ExpectedData'], data_snapshot(docker))
    verify_version(repo, state['Version'])
    reset_default_site_password(docker, state['StagingPort'])
    env = repo / 'deploy/.env'
    text = set_env(env.read_text(encoding='utf-8-sig'), 'HOJ_WEB_BIND', '0.0.0.0')
    text = set_env(text, 'HOJ_WEB_PORT', state['PublicPort'])
    write_env(env, text)
    state['EnvHash'] = hb.file_hash(env)
    hb.write_json(path, state)
    start(docker, files_for(repo), env)
    verify_version(repo, state['Version'])
    state['Status'] = 'published'
    hb.write_json(path, state)
    print('MIGRATION PUBLISHED: ' + state['Version'] + ' on port ' + str(state['PublicPort']))


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    recover = commands.add_parser('import')
    recover.add_argument('bundle')
    recover.add_argument('--release', required=True)
    recover.add_argument('--repo', default=str(REPO))
    recover.add_argument('--output', default='/root/hoj-migrations')
    recover.add_argument('--port', type=int, default=80)
    recover.add_argument('--staging-port', type=int, default=18082)
    recover.add_argument('--subnet', default='172.30.247.0/24')
    recover.add_argument('--publish', action='store_true')
    for name in ('publish', 'undo'):
        command = commands.add_parser(name)
        target = command.add_mutually_exclusive_group(required=True)
        target.add_argument('--state')
        target.add_argument('--latest', action='store_true')
    args = parser.parse_args()
    docker = hb.Docker()
    server_only(docker)
    if args.action == 'import':
        if not 1 <= args.port <= 65535 or not 1024 <= args.staging_port <= 65535 or args.port == args.staging_port:
            raise RuntimeError('Invalid public/staging ports.')
        import_server(docker, args)
    elif args.action == 'publish':
        publish(docker, args)
    else:
        path, state, _ = checked_state(args)
        restore_previous(docker, state, path.parent)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print('SERVER MIGRATION FAILED: ' + str(error), file=sys.stderr)
        sys.exit(1)
