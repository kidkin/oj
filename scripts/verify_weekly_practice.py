#!/usr/bin/env python3
"""Retained weekly-practice demo and acceptance checks; Windows hoj-dev only."""
import datetime as dt
import json
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
    mysql=docker.json('container','inspect','hoj-dev-hoj-mysql-1')[0]
    assert mysql['Config']['Labels']['com.docker.compose.project']=='hoj-dev'
    def sql(query):
        return hb.run([docker.exe,'exec','-i',mysql['Id'],'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names hoj'],stdin=query).stdout.strip()
    before={table:set(sql('SELECT %s FROM %s;'%(key,table)).splitlines()) for table,key in (('user_info','uuid'),('problem','id'),('judge','submit_id'),('training','id'))}
    relations={table:sql('SELECT * FROM %s ORDER BY id;'%table) for table in ('edu_class_member','edu_class_course','edu_course_problem','edu_course_problem_display')}
    demo=hb.read_json(REPO/'backups/course-catalog-demo/state.json')
    root=Client().login(os.environ.get('HOJ_TEST_USERNAME','root'),os.environ.get('HOJ_TEST_PASSWORD','hoj123456'))
    students=[Client().login(demo['Users'][key]['Username'],demo['Password']) for key in ('student','student2','outsider')]
    folder=REPO/'backups/weekly-demo';folder.mkdir(parents=True,exist_ok=True)
    statefile=folder/'state.json'; state=hb.read_json(statefile) if statefile.exists() else {'Trainings':{},'Category':None}
    def save(): hb.write_json(statefile,state)
    if not state['Category']:
        categories=root.call('/api/get-training-category')
        category=next((item for item in categories if item['name']=='周周练演示'),None)
        if category is None: category=root.call('/api/admin/training/category','POST',{'name':'周周练演示','color':'#438bc4'})
        state['Category']=category['id'];save()
    now=dt.datetime.now(dt.timezone(dt.timedelta(hours=8)))
    # Deliberately create in a different order from publication dates.
    for index,days in ((3,0),(1,14),(2,7),(4,21),(5,28)):
        key=str(index)
        if key not in state['Trainings']:
            title='周周练演示 · 第%d期'%index
            release=int((now-dt.timedelta(days=days,minutes=5)).timestamp()*1000)
            training={'title':title,'description':'演示题集，供检查周周练页面。实际题目及各人的练习范围由教师安排。','author':'root','rank':1000-index,'auth':'Private' if index==4 else 'Public','privatePwd':'weekly-demo-only' if index==4 else '', 'status':index!=5,'isGroup':False,'publishTime':release}
            root.call('/api/admin/training/training','POST',{'training':training,'trainingCategory':{'id':state['Category']}})
            found=root.call('/api/admin/training/get-training-list?'+urlencode({'keyword':title,'limit':20}))['records']
            assert len(found)==1
            state['Trainings'][key]=found[0]['id'];save()
    ids=[int(demo['Problems'][str(number)]) for number in (1,2,3)]
    for index in (1,2,3):
        tid=state['Trainings'][str(index)]
        current=root.call('/api/admin/training/get-problem-list?'+urlencode({'tid':tid,'limit':50,'queryExisted':'true'}))
        mapping=current['trainingProblemMap']
        for pid,number in zip(ids,(30,2,10)):
            if str(pid) not in mapping:
                root.call('/api/admin/training/add-problem-from-public','POST',{'tid':tid,'pid':pid,'displayId':'WEEKLY_%d'%pid})
        current=root.call('/api/admin/training/get-problem-list?'+urlencode({'tid':tid,'limit':50,'queryExisted':'true'}))
        for pid,number in zip(ids,(30,2,10)):
            entry=current['trainingProblemMap'][str(pid)];entry['rank']=number
            root.call('/api/admin/training/update-problem','PUT',entry)
    query={'auth':'Public','keyword':'周周练演示','limit':2,'currentPage':1}
    first=students[0].call('/api/get-training-list?'+urlencode(query))
    assert first['total']==3 and [item['id'] for item in first['records']]==[state['Trainings']['3'],state['Trainings']['2']]
    query['currentPage']=2
    second=students[0].call('/api/get-training-list?'+urlencode(query))
    assert [item['id'] for item in second['records']]==[state['Trainings']['1']]
    tid=state['Trainings']['3']
    for actor in students+[Client()]:
        detail=actor.call('/api/get-training?tid=%d'%tid)
        assert detail['auth']=='Public' and detail['problemCount']==3
        questions=actor.call('/api/get-training-problem-list?tid=%d'%tid)
        assert [item['trainingRank'] for item in questions]==[2,10,30]
        assert [item['pid'] for item in questions]==[ids[1],ids[2],ids[0]]
    # Editing content must not make an old issue look freshly published.
    oldtid=state['Trainings']['1']
    old=root.call('/api/admin/training/training?tid=%d'%oldtid)
    publication=old['training']['publishTime']
    root.call('/api/admin/training/training','PUT',old)
    assert root.call('/api/get-training?tid=%d'%oldtid)['publishTime']==publication
    old['training']['publishTime']=int(now.timestamp()*1000)+86400000
    root.call('/api/admin/training/training','PUT',old,expected=400)
    students[0].call('/api/admin/training/training','POST',{},expected=403)
    invalid=root.call('/api/admin/training/get-problem-list?'+urlencode({'tid':tid,'limit':50,'queryExisted':'true'}))['trainingProblemMap'][str(ids[0])]
    invalid['rank']=-1
    root.call('/api/admin/training/update-problem','PUT',invalid,expected=400)
    for table,key in (('user_info','uuid'),('problem','id'),('judge','submit_id'),('training','id')):
        assert before[table].issubset(set(sql('SELECT %s FROM %s;'%(key,table)).splitlines()))
    assert relations=={table:sql('SELECT * FROM %s ORDER BY id;'%table) for table in relations}
    versions=sql('SELECT version FROM edu_schema_history ORDER BY version;').splitlines()
    assert versions==['V001__class_members','V002__shared_courses','V003__course_icons','V004__display_order','V005__weekly_release']
    report={'Passed':True,'PublicationOrderAcrossPages':True,'OriginalQuestionNumbersHiddenByUi':True,'NumericQuestionOrder':[2,10,30],'AllStudentsAndAnonymousPublicAccess':True,'PrivateAndHiddenNotListed':True,'EditingKeepsPublicationDate':True,'FutureDateRejected':True,'StudentCannotPublish':True,'OriginalDataAndRelationshipsPreserved':True,'Migrations':versions,'VisibleDemoIssues':3,'RetainedDemoTrainings':state['Trainings']}
    hb.write_json(REPO/'logs/weekly-api-acceptance.json',report)
    print('WEEKLY API PASSED: publication ordering, numeric question IDs, shared access, validation, migration and original data retention')

if __name__=='__main__':
    os.umask(0o077)
    try:main()
    except (RuntimeError,OSError,ValueError,AssertionError) as error:
        print('WEEKLY CHECK FAILED: '+str(error),file=sys.stderr);sys.exit(1)
