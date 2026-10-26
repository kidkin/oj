#!/usr/bin/env python3
"""Read-only acceptance of course membership and administrative problem sources."""
import os
from pathlib import Path
import sys
from urllib.parse import urlencode
import hoj_backup as hb
import restore_dev
from verify_classes import Client

REPO=Path(__file__).resolve().parents[1]

def main():
    docker=hb.Docker(); restore_dev.local_windows_only(docker)
    demo=hb.read_json(REPO/'backups/course-catalog-demo/state.json')
    root=Client().login(os.environ.get('HOJ_TEST_USERNAME','root'),os.environ.get('HOJ_TEST_PASSWORD','hoj123456'))
    clients={key:Client().login(demo['Users'][key]['Username'],demo['Password']) for key in ('student','student2','outsider','teacher')}
    expected=set(demo['Courses'].values())
    catalog=clients['student2'].call('/api/edu/courses?scope=catalog&limit=50')['records']
    ours=[row for row in catalog if row['id'] in expected]
    joined=[row for row in ours if row['canAccess']]
    restricted=[row for row in ours if not row['canAccess']]
    assert len(joined)==2 and len(restricted)==2
    for row in joined:
        assert row['sourceClasses']
        detail=clients['student2'].call('/api/edu/courses/%d'%row['id'])
        assert detail['canAccess'] and not detail['canEdit']
        assert detail['problemCount']==len(detail['problems'])
    for row in restricted:
        assert not row['sourceClasses'] and 'problems' not in row
        clients['student2'].call('/api/edu/courses/%d'%row['id'],expected=403)
    for row in clients['outsider'].call('/api/edu/courses?scope=catalog&limit=50')['records']:
        assert not row['canAccess']
    path='/api/get-problem-detail?'+urlencode({'problemId':'EDU_DEMO22_01'})
    source=root.call(path)['problem']['source'];assert source
    for viewer in list(clients.values())+[Client()]:
        problem=viewer.call(path)['problem']
        assert problem.get('source') is None and problem['title']
    assert root.call(path)['problem']['source']==source
    record={'Passed':True,'EnrolledCatalogCoursesAccessible':True,'UnenrolledCoursesDenied':True,'MembershipChecksUnchanged':True,'StudentsTeachersAndAnonymousDoNotReceiveSource':True,'AdministratorSourceRetained':True,'ExistingCourseIds':sorted(expected),'JoinedStudent2Courses':[row['id'] for row in joined],'RestrictedStudent2Courses':[row['id'] for row in restricted]}
    hb.write_json(REPO/'logs/learning-access-api-acceptance.json',record)
    print('LEARNING ACCESS PASSED: enrolled course entry, unenrolled denial, student source redaction and administrator access')

if __name__=='__main__':
    os.umask(0o077)
    try:main()
    except (RuntimeError,OSError,ValueError,AssertionError) as error:
        print('LEARNING ACCESS FAILED: '+str(error),file=sys.stderr);sys.exit(1)
