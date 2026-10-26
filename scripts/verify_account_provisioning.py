#!/usr/bin/env python3
"""Local acceptance of administrator-only account creation. Demo data is retained."""
import os
from pathlib import Path
import secrets
import sys
import hoj_backup as hb
import restore_dev
from verify_classes import Client

REPO = Path(__file__).resolve().parents[1]


def main():
    docker = hb.Docker()
    restore_dev.local_windows_only(docker)
    root = Client().login(os.environ.get('HOJ_TEST_USERNAME', 'root'),
                          os.environ.get('HOJ_TEST_PASSWORD', 'hoj123456'))
    demo = hb.read_json(REPO / 'backups/course-catalog-demo/state.json')
    viewers = [Client()]
    for role in ('student', 'teacher'):
        viewers.append(Client().login(demo['Users'][role]['Username'], demo['Password']))
    assert Client().call('/api/get-website-config')['register'] is False
    denied_account = {'username': 'registration_closed', 'password': secrets.token_urlsafe(9),
                      'email': 'closed@example.com', 'code': '123456'}
    for viewer in viewers:
        viewer.call('/api/register', 'POST', denied_account, expected=401)
        viewer.call('/api/get-register-code?email=closed%40example.com', expected=401)
        viewer.call('/api/admin/user/create-user', 'POST', denied_account, expected=(401, 403))

    folder = REPO / 'backups/account-provisioning-demo'
    folder.mkdir(parents=True, exist_ok=True)
    state_file = folder / 'state.json'
    if state_file.is_file():
        state = hb.read_json(state_file)
    else:
        state = {'Username': 'demo26_single_' + secrets.token_hex(3),
                 'Password': secrets.token_urlsafe(12), 'Realname': '单账号测试学生', 'Created': False}
        hb.write_json(state_file, state)
    payload = {'username': state['Username'], 'password': state['Password'], 'realname': state['Realname']}
    if not state['Created']:
        # Extra role fields must never elevate the ordinary account.
        root.call('/api/admin/user/create-user', 'POST', dict(payload, type=1000, roleId=1000))
        state['Created'] = True
        hb.write_json(state_file, state)
    root.call('/api/admin/user/create-user', 'POST', dict(payload, password='OtherPassword123'), expected=400)
    for change in ({'username': ''}, {'username': 'bad name'}, {'username': 'bad@name'},
                   {'username': 'x' * 21}, {'password': ''}, {'password': 'short'},
                   {'password': 'x' * 21}, {'password': ' leading123'}, {'realname': '名' * 51}):
        root.call('/api/admin/user/create-user', 'POST', dict(payload, **change), expected=400)
    created = Client()
    profile = created.call('/api/login', 'POST', {'username': state['Username'], 'password': state['Password']})
    assert profile['roleList'] == ['default_user'] and profile['realname'] == state['Realname']
    created.call('/api/admin/user/create-user', 'POST', payload, expected=(401, 403))

    query = ("SELECT ui.username, ur.role_id, (SELECT COUNT(*) FROM user_record r WHERE r.uid=ui.uuid) "
             "FROM user_info ui JOIN user_role ur ON ur.uid=ui.uuid WHERE ui.username="
             "CONVERT(0x%s USING utf8mb4);" % state['Username'].encode().hex())
    result = hb.run([docker.exe, 'exec', '-i', 'hoj-dev-hoj-mysql-1', 'sh', '-c',
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names hoj'],
                    stdin=query).stdout.strip()
    assert result == state['Username'] + '\t1002\t1'
    report = {'Passed': True, 'RegistrationAndEmailDisabled': True, 'AnonymousStudentTeacherCreationDenied': True,
              'SingleAccountLoginPassed': True, 'DuplicateDidNotChangePassword': True,
              'InvalidInputsDenied': True, 'OrdinaryRoleAndStatisticsCreated': True,
              'ExtraRoleFieldsIgnored': True, 'RetainedUsername': state['Username']}
    hb.write_json(REPO / 'logs/account-provisioning-acceptance.json', report)
    (folder / 'account.txt').write_text('Retained local demo account\nUsername: ' + state['Username'] +
                                      '\nPassword: ' + state['Password'] + '\n', encoding='utf-8')
    os.chmod(folder / 'account.txt', 0o600)
    print('ACCOUNT PROVISIONING PASSED: public signup disabled; privileged creation, input validation and login verified.')


if __name__ == '__main__':
    os.umask(0o077)
    try:
        main()
    except (RuntimeError, OSError, ValueError, AssertionError, KeyError) as error:
        print('ACCOUNT PROVISIONING FAILED: ' + str(error), file=sys.stderr)
        sys.exit(1)
