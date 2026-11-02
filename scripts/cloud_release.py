#!/usr/bin/env python3
"""Assemble cloud-built HOJ releases on Ubuntu; keep data volumes and rollback configuration.

Used by a trusted SSH orchestration wrapper with a REQUEST dictionary. No credentials
are accepted in REQUEST; Docker uses the server's existing registry login.
"""
import base64,datetime,hashlib,json,os,re,shutil,subprocess,sys,tarfile,time
from pathlib import Path,PurePosixPath

REPO=Path('/root/oj')
REGISTRY='crpi-06ni3fl9fozs6y04.cn-shanghai.personal.cr.aliyuncs.com/kidkin-oj'
APPLICATIONS=('hoj-backend','hoj-frontend','hoj-judgeserver')


def digest(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda:source.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()


def run(args,data=None,timeout=600,log=None):
    if log:
        with Path(log).open('ab') as stream:p=subprocess.run([str(x) for x in args],input=data,stdout=stream,stderr=stream,timeout=timeout)
        if p.returncode:raise RuntimeError('Server operation failed; private log: '+str(log))
        return b''
    p=subprocess.run([str(x) for x in args],input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if p.returncode:raise RuntimeError('Server operation failed: '+p.stderr.decode('utf-8','replace')[-1000:])
    return p.stdout


def save(path,value):
    path=Path(path);tmp=path.with_suffix(path.suffix+'.pending');tmp.write_text(json.dumps(value,indent=2),encoding='utf-8');os.chmod(tmp,0o600);os.replace(tmp,path)


def services():
    ids=run(['docker','ps','-aq','--filter','label=com.docker.compose.project=hoj-dev']).decode().split()
    rows=json.loads(run(['docker','inspect',*ids]))
    result={r['Config']['Labels']['com.docker.compose.service']:r for r in rows}
    if len(result)!=7:raise RuntimeError('Expected the existing seven-service hoj-dev deployment.')
    return result


def compose(*args,log=None):
    files=[REPO/'deploy/compose.dev.yml',REPO/'deploy/compose.prebuilt.yml',REPO/'deploy/compose.dev-data.json']
    cmd=['docker','compose','--project-name','hoj-dev','--env-file',REPO/'deploy/.env']
    for file in files:
        if file.is_file():cmd+=['-f',file]
    return run(cmd+list(args),timeout=600,log=log)


def sql(text):
    ss=services();env=dict(v.split('=',1) for v in ss['hoj-backend']['Config']['Env'] if '=' in v)
    database=env.get('MYSQL_DATABASE_NAME','hoj')
    if not re.fullmatch(r'[A-Za-z0-9_]+',database):raise RuntimeError('Unexpected active database name.')
    return run(['docker','exec','-i',ss['hoj-mysql']['Id'],'sh','-c','export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4 --batch --raw --skip-column-names "$@"','cloud-release',database],text.encode())


def snapshot():
    result={}
    for table,key in [('user_info','uuid'),('problem','id'),('judge','submit_id'),('edu_class','id'),('edu_course','id'),('edu_class_member','id'),('edu_class_course','id'),('edu_course_problem','id')]:
        values=sql('SELECT `'+key+'` FROM `'+table+'` ORDER BY `'+key+'`;').decode().splitlines()
        result[table]={'key':key,'ids':values,'count':len(values)}
    maximum=int(sql('SELECT IFNULL(MAX(submit_id),0) FROM judge;'))
    q='SELECT submit_id,uid,pid,cid,gid,SHA2(code,256),status,IFNULL(score,-1) FROM judge WHERE submit_id<='+str(maximum)+' ORDER BY submit_id;'
    result['History']={'Maximum':maximum,'Sha256':hashlib.sha256(sql(q)).hexdigest()}
    return result


def verify_data(before):
    current=snapshot()
    for table,record in before.items():
        if table=='History':continue
        if not set(record['ids']).issubset(set(current[table]['ids'])):raise RuntimeError('Existing record IDs disappeared: '+table)
    q='SELECT submit_id,uid,pid,cid,gid,SHA2(code,256),status,IFNULL(score,-1) FROM judge WHERE submit_id<='+str(before['History']['Maximum'])+' ORDER BY submit_id;'
    if hashlib.sha256(sql(q)).hexdigest()!=before['History']['Sha256']:raise RuntimeError('Existing submission content or results changed unexpectedly.')
    return {key:value['count'] for key,value in current.items() if key!='History'}


def unpack(path,destination,expected=None):
    destination.mkdir(mode=0o700,exist_ok=True)
    with tarfile.open(path,'r:gz') as stream:
        seen=set()
        for member in stream:
            p=PurePosixPath(member.name)
            if p.is_absolute() or '..' in p.parts or not(member.isdir() or member.isfile()) or member.size>512*1024*1024:raise RuntimeError('Unsafe application archive.')
            seen.add(member.name);target=destination.joinpath(*p.parts)
            if member.isdir():target.mkdir(parents=True,exist_ok=True);continue
            target.parent.mkdir(parents=True,exist_ok=True)
            if target.exists():raise RuntimeError('Application extraction destination already exists.')
            with stream.extractfile(member) as source,target.open('xb') as output:shutil.copyfileobj(source,output)
        if expected is not None and seen!=expected:raise RuntimeError('Unexpected cloud application package file list.')


def main(request):
    if not sys.platform.startswith('linux') or os.geteuid()!=0:raise RuntimeError('Ubuntu root with local Docker is required.')
    host=json.loads(run(['docker','context','inspect']))[0]['Endpoints']['docker']['Host']
    if not host.startswith('unix://'):raise RuntimeError('Refusing a remote Docker engine.')
    version=request.get('version','v0.3.2')
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+',version):raise RuntimeError('Invalid release version.')
    folder=Path('/root/hoj-cloud-releases')/version;folder.mkdir(parents=True,mode=0o700,exist_ok=True)
    statefile=folder/'state.json';state=json.loads(statefile.read_text()) if statefile.exists() else {}
    action=request['action']
    if action=='init':
        if state:return {'state':state.get('Status'),'directory':str(folder),'already_initialized':True}
        if run(['git','-C',REPO,'status','--porcelain']).strip():raise RuntimeError('Server source has uncommitted changes.')
        current=json.loads(compose('config','--format','json'))
        if current['name']!='hoj-dev':raise RuntimeError('Wrong deployment project.')
        for name in APPLICATIONS:
            if services()[name]['Config']['Image'].split('/hoj-')[0]!=REGISTRY:raise RuntimeError('Unexpected application registry.')
        shutil.copyfile(REPO/'deploy/.env',folder/'previous.env');os.chmod(folder/'previous.env',0o600)
        shutil.copyfile(REPO/'deploy/compose.dev-data.json',folder/'previous.override.json');os.chmod(folder/'previous.override.json',0o600)
        save(folder/'previous.compose.private.json',current)
        state={'Version':version,'Status':'initialized','PreviousHead':run(['git','-C',REPO,'rev-parse','HEAD']).decode().strip(),'PreviousReferences':{key:services()[key]['Config']['Image'] for key in APPLICATIONS},'EnvironmentHash':digest(REPO/'deploy/.env'),'OverrideHash':digest(REPO/'deploy/compose.dev-data.json')}
        save(statefile,state);return {'state':'initialized','directory':str(folder)}
    if not state:raise RuntimeError('Initialize the existing deployment first.')
    if action=='append':
        name=request['name'];offset=request['offset']
        if name not in ('source.bundle','release.bundle','applications.tar.gz') or not isinstance(offset,int) or offset<0:raise RuntimeError('Invalid upload request.')
        target=folder/name;block=base64.b64decode(request['base64'],validate=True)
        if len(block)>2*1024*1024 or (target.stat().st_size if target.exists() else 0)!=offset:raise RuntimeError('Upload offset differs; refusing overwrite.')
        with target.open('ab') as stream:stream.write(block)
        return {'bytes':target.stat().st_size}
    if action=='finish':
        name=request['name']
        if name not in ('source.bundle','release.bundle','applications.tar.gz') or digest(folder/name)!=request['sha256']:raise RuntimeError('Uploaded file checksum mismatch.')
        state.setdefault('VerifiedFiles',{})[name]=request['sha256'];save(statefile,state)
        return {'name':name,'sha256':request['sha256'],'bytes':(folder/name).stat().st_size}
    if action=='source':
        name=request['name'];commit=request['commit']
        if name not in ('source.bundle','release.bundle') or not re.fullmatch(r'[0-9a-f]{40}',commit):raise RuntimeError('Invalid source bundle request.')
        if digest(folder/name)!=state.get('VerifiedFiles',{}).get(name):raise RuntimeError('Source bundle has not been verified.')
        run(['git','-C',REPO,'bundle','verify',folder/name])
        run(['git','-C',REPO,'fetch',folder/name,request['ref']])
        run(['git','-C',REPO,'switch','--detach',commit])
        if run(['git','-C',REPO,'rev-parse','HEAD']).decode().strip()!=commit:raise RuntimeError('Source checkout mismatch.')
        state['DeployCommit']=commit;save(statefile,state);return {'source_commit':commit}
    if action=='assemble':
        if state.get('Release'):return state['Release']
        package=folder/'applications.tar.gz'
        if digest(package)!=state.get('VerifiedFiles',{}).get(package.name):raise RuntimeError('Application package has not been verified.')
        apps=folder/'applications';unpack(package,apps,{'backend.jar','judgeserver.jar','frontend.tar.gz','build.json'})
        build=json.loads((apps/'build.json').read_text());source=build['SourceCommit']
        if build['Tests']['tests']<59 or any(build['Tests'][key] for key in ('failures','errors','skipped')):raise RuntimeError('Cloud tests have not passed.')
        for path,tree in build['ApplicationTrees'].items():
            if path not in ('hoj-vue','hoj-springboot') or run(['git','-C',REPO,'rev-parse','HEAD:'+path]).decode().strip()!=tree:raise RuntimeError('Application source tree mismatch.')
        for name,spec in build['Files'].items():
            if name not in ('backend.jar','judgeserver.jar','frontend.tar.gz') or digest(apps/name)!=spec['Sha256']:raise RuntimeError('Compiled application checksum mismatch.')
        unpack(apps/'frontend.tar.gz',apps/'frontend')
        records={}
        for service in ('hoj-backend','hoj-judgeserver','hoj-frontend'):
            ref=REGISTRY+'/'+service+':'+version;short=service[4:]
            args=['docker','build','--pull=false','--network','none','--build-arg','SOURCE_REVISION='+source,'-t',ref]
            if short=='frontend':args+=['-f',REPO/'deploy/Dockerfile.cloud-frontend','--build-arg','FRONTEND_RUNTIME_IMAGE='+state['PreviousReferences'][service]]
            else:args+=['-f',REPO/'deploy/Dockerfile.cloud-java','--target',short,'--build-arg',short.upper()+'_RUNTIME_IMAGE='+state['PreviousReferences'][service]]
            run(args+[apps],timeout=600,log=folder/(service+'-build.private.log'))
            run(['docker','push',ref],timeout=600,log=folder/(service+'-push.private.log'))
            image=json.loads(run(['docker','image','inspect',ref]))[0]
            if image['Config']['Labels'].get('org.opencontainers.image.revision')!=source:raise RuntimeError('Published image source differs from cloud build.')
            ds=[r for r in image.get('RepoDigests',[]) if r.startswith(REGISTRY+'/'+service+'@sha256:')]
            if len(ds)!=1:raise RuntimeError('Published image digest is missing or ambiguous.')
            records[service]={'Reference':ref,'Digest':ds[0].split('@')[1],'SourceRevision':source,'RootFsDiffIds':image['RootFS']['Layers']}
        release={'Version':version,'ReleasedOn':datetime.date.today().isoformat(),'Images':records,'Migrations':{p.stem:digest(p) for p in sorted((REPO/'hoj-springboot/DataBackup/src/main/resources/db/edu').glob('V*.sql'))},'CloudBuild':{'SourceCommit':source,'ApplicationTrees':build['ApplicationTrees'],'Tests':build['Tests']}}
        save(folder/'release.json',release);state['Release']=release;state['Status']='images-published';save(statefile,state);return release
    if action=='backup':
        if state.get('Backup'):return {'backup':state['Backup'],'already_completed':True}
        out=folder/'backups';out.mkdir(mode=0o700)
        run(['python3',REPO/'scripts/hoj_backup.py','backup','--repo',REPO,'--output',out],timeout=1500,log=folder/'backup.private.log')
        archives=list(out.glob('*.tar'))
        if len(archives)!=1:raise RuntimeError('Expected one completed rollback backup.')
        archive=archives[0];expected=Path(str(archive)+'.sha256').read_text().split()[0]
        if digest(archive)!=expected:raise RuntimeError('Rollback backup checksum mismatch.')
        state['Backup']=str(archive);state['BackupSha256']=expected;save(statefile,state)
        return {'backup':str(archive),'sha256':expected}
    if action=='undo':
        compose('stop','--timeout','30',*APPLICATIONS,log=folder/'rollback.private.log')
        shutil.copyfile(folder/'previous.env',REPO/'deploy/.env');os.chmod(REPO/'deploy/.env',0o600)
        shutil.copyfile(folder/'previous.override.json',REPO/'deploy/compose.dev-data.json');os.chmod(REPO/'deploy/compose.dev-data.json',0o600)
        run(['docker','compose','--project-name','hoj-dev','-f',folder/'previous.compose.private.json','up','-d','--no-build','--pull','never','--wait','--wait-timeout','360'],log=folder/'rollback.private.log')
        state['Status']='rolled-back';save(statefile,state);return {'state':'rolled-back','data_volumes_retained':True}
    if action=='switch':
        if not state.get('Backup') or not state.get('Release'):raise RuntimeError('Verified release and rollback backup are required.')
        if state.get('Status')=='published':return {'state':'published','already_completed':True}
        if digest(REPO/'deploy/.env')!=state['EnvironmentHash'] or digest(REPO/'deploy/compose.dev-data.json')!=state['OverrideHash']:raise RuntimeError('Deployment configuration changed after preparation.')
        if json.loads((REPO/'releases'/(version+'.json')).read_text())!=state['Release']:raise RuntimeError('Tracked Git release manifest differs from published images.')
        try:
            compose('stop','--timeout','25','hoj-frontend',log=folder/'switch.private.log')
            for _ in range(30):
                if int(sql('SELECT COUNT(*) FROM judge WHERE status IN (5,6,7);'))==0:break
                time.sleep(2)
            else:raise RuntimeError('Existing judging jobs have not finished.')
            compose('stop','--timeout','30','hoj-backend','hoj-judgeserver',log=folder/'switch.private.log')
            before=snapshot();save(folder/'before-switch.private.json',before)
            text=(REPO/'deploy/.env').read_text()
            for service,record in state['Release']['Images'].items():
                key='HOJ_'+service[4:].upper()+'_IMAGE';value=record['Reference'].rsplit(':',1)[0]+'@'+record['Digest'];line=key+'='+value
                text=re.sub(r'(?m)^'+key+r'=.*$',lambda _:line,text) if re.search(r'(?m)^'+key+'=',text) else text.rstrip()+'\n'+line+'\n'
            temp=REPO/'deploy/.env.cloud-update';temp.write_text(text,encoding='utf-8');os.chmod(temp,0o600);os.replace(temp,REPO/'deploy/.env')
            old=json.loads((folder/'previous.compose.private.json').read_text());new=json.loads(compose('config','--format','json'))
            if new['volumes']!=old['volumes']:raise RuntimeError('Update unexpectedly changed data volume mappings.')
            compose('up','-d','--no-build','--pull','never','--wait','--wait-timeout','360',log=folder/'switch.private.log')
            run(['python3',REPO/'scripts/verify_release.py','--release',version],log=folder/'verify.private.log')
            counts=verify_data(before)
            state['Status']='published';state['PublishedAt']=datetime.datetime.now().isoformat();state['Counts']=counts;state['AfterEnvironmentHash']=digest(REPO/'deploy/.env');save(statefile,state)
            return {'state':'published','release':version,'database_and_volumes_preserved':True,'existing_submission_history_preserved':True,'counts':counts,'rollback_record':str(statefile),'backup':state['Backup']}
        except BaseException:
            main({'action':'undo','version':version});raise
    raise RuntimeError('Unsupported cloud release action.')


if __name__=='__main__':
    if 'REQUEST' not in globals():raise SystemExit('Run through the authenticated deployment wrapper with an explicit REQUEST.')
    print(json.dumps(main(REQUEST)))
