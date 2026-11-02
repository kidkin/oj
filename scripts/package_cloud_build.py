#!/usr/bin/env python3
"""Package cloud-compiled applications with source and test provenance; no deployment secrets."""
import argparse,hashlib,json,os,re,shutil,subprocess,tarfile,xml.etree.ElementTree as ET
from pathlib import Path


def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda:source.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()


def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    repo=Path(__file__).resolve().parents[1]
    def git(*args):return subprocess.check_output(['git','-C',str(repo),*args],text=True).strip()
    commit=git('rev-parse','HEAD')
    if not re.fullmatch(r'[a-f0-9]{40}',commit) or os.environ.get('GITHUB_SHA',commit)!=commit:raise RuntimeError('Build commit does not match the cloud run.')
    tests={'tests':0,'failures':0,'errors':0,'skipped':0}
    reports=list((repo/'hoj-springboot/DataBackup/target/surefire-reports').glob('TEST-*.xml'))
    if not reports:raise RuntimeError('Unit test reports are missing.')
    for report in reports:
        root=ET.parse(report).getroot()
        for key in tests:tests[key]+=int(root.attrib.get(key,0))
    if tests['tests']<59 or tests['failures'] or tests['errors'] or tests['skipped']:raise RuntimeError('Expected all education tests to pass before packaging.')
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    staging=output/'applications';staging.mkdir()
    shutil.copyfile(repo/'hoj-springboot/DataBackup/target/hoj-backend-4.6.jar',staging/'backend.jar')
    shutil.copyfile(repo/'hoj-springboot/JudgeServer/target/hoj-judgeServer-4.6.jar',staging/'judgeserver.jar')
    front=repo/'hoj-vue/dist'
    if not (front/'index.html').is_file():raise RuntimeError('Compiled frontend is missing.')
    with tarfile.open(staging/'frontend.tar.gz','w:gz') as archive:
        for file in sorted(front.iterdir()):archive.add(file,arcname=file.name)
    manifest={'Schema':1,'SourceCommit':commit,'ApplicationTrees':{name:git('rev-parse','HEAD:'+name) for name in ('hoj-vue','hoj-springboot')},'Tests':tests,'Files':{file.name:{'Bytes':file.stat().st_size,'Sha256':sha(file)} for file in sorted(staging.iterdir())}}
    (staging/'build.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    shutil.copyfile(staging/'build.json',output/'build.json')
    archive=output/'hoj-applications.tar.gz'
    with tarfile.open(archive,'w:gz') as stream:
        for file in sorted(staging.iterdir()):stream.add(file,arcname=file.name)
    Path(str(archive)+'.sha256').write_text(sha(archive)+'  '+archive.name+'\n',encoding='ascii')
    print('VERIFIED CLOUD APPLICATIONS:',commit,'tests:',tests['tests'],'bytes:',archive.stat().st_size)


if __name__=='__main__':main()
