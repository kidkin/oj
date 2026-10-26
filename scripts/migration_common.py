"""Shared private-package and read-only data checks for HOJ migration."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile
import hoj_backup as hb

CORE_TABLES = (
    'user_info', 'user_role', 'user_record', 'problem', 'problem_case', 'judge',
    'edu_class', 'edu_class_member', 'edu_course', 'edu_class_course', 'edu_course_problem',
    'edu_course_icon', 'edu_class_display', 'edu_course_display', 'edu_course_problem_display',
    'edu_weekly_release', 'training', 'training_problem', 'training_register', 'contest', 'contest_problem',
)


def sql(docker, project, statement):
    return hb.run([docker.exe, 'exec', '-i', project + '-hoj-mysql-1', 'sh', '-c',
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names hoj'],
                  stdin=statement).stdout.strip()


def data_snapshot(docker, project='hoj-dev'):
    tables = ','.join("'" + name + "'" for name in CORE_TABLES)
    metadata = sql(docker, project,
        "SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='hoj' "
        "AND COLUMN_KEY='PRI' AND TABLE_NAME IN (" + tables + ") ORDER BY TABLE_NAME,ORDINAL_POSITION;")
    columns = {}
    for row in metadata.splitlines():
        table, column = row.split('\t')
        if table not in CORE_TABLES or not re.fullmatch(r'[A-Za-z0-9_]+', column):
            raise RuntimeError('Unexpected database metadata.')
        columns.setdefault(table, []).append(column)
    result = {}
    for table, keys in sorted(columns.items()):
        expressions = ','.join('HEX(`' + key + '`)' for key in keys)
        rows = sql(docker, project, "SELECT CONCAT_WS(','," + expressions + ") FROM `" + table + '`;').splitlines()
        result[table] = {'Count': len(rows),
                         'PrimaryKeysSHA256': hashlib.sha256('\n'.join(sorted(rows)).encode()).hexdigest()}
    if not {'user_info', 'problem', 'judge'}.issubset(result):
        raise RuntimeError('Core user/problem/submission tables are missing.')
    return result


def verify_data(expected, actual):
    for table, record in expected.items():
        if actual.get(table) != record:
            raise RuntimeError('Imported data count or identifiers differ: ' + table)


def unpack_bundle(path, destination):
    """Extract three flat files only; never use ZipFile.extractall."""
    destination = Path(destination)
    with zipfile.ZipFile(path) as package:
        entries = package.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)) or len(names) != 3 or 'migration.json' not in names:
            raise RuntimeError('Unexpected migration package file list.')
        request_entry = package.getinfo('migration.json')
        if request_entry.file_size > 1024 * 1024:
            raise RuntimeError('Migration metadata is too large.')
        request = json.loads(package.read(request_entry).decode('utf-8'))
        archive = request.get('Archive', '')
        if not re.fullmatch(r'hoj-backup-[0-9-]+\.tar', archive):
            raise RuntimeError('Invalid backup filename in migration package.')
        if set(names) != {'migration.json', archive, archive + '.sha256'}:
            raise RuntimeError('Unexpected migration package member.')
        if (request.get('Schema') != 1 or not re.fullmatch(r'[a-f0-9]{64}', request.get('BackupSHA256', ''))
                or not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', request.get('Version', ''))):
            raise RuntimeError('Invalid migration metadata.')
        for entry in entries:
            if entry.is_dir() or entry.file_size > 64 * 1024**3 or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise RuntimeError('Unsafe migration package member.')
            with package.open(entry) as source, open(destination / entry.filename, 'xb') as target:
                shutil.copyfileobj(source, target)
    if hb.file_hash(destination / archive) != request['BackupSHA256']:
        raise RuntimeError('Migration backup checksum failed.')
    return request
