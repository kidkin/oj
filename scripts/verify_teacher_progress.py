#!/usr/bin/env python3
"""Retained local teacher-report demo and API acceptance checks. No cleanup deletes."""
import json
import os
from pathlib import Path
import sys
import time
import hoj_backup as hb
import restore_dev
from verify_classes import Client

REPO=Path(__file__).resolve().parents[1]


def main():
    docker=hb.Docker(); restore_dev.local_windows_only(docker)
    mysql=docker.json("container","inspect","hoj-dev-hoj-mysql-1")[0]
    assert mysql["Config"]["Labels"]["com.docker.compose.project"]=="hoj-dev"
    def sql(query):
        return hb.run([docker.exe,"exec","-i",mysql["Id"],"sh","-c",'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names hoj'],stdin=query).stdout.strip()
    before={table:set(sql("SELECT %s FROM %s;"%(key,table)).splitlines()) for table,key in (("user_info","uuid"),("problem","id"),("judge","submit_id"))}
    relations={table:sql("SELECT * FROM %s ORDER BY id;"%table) for table in ("edu_class_member","edu_class_course")}
    demo=hb.read_json(REPO/"backups/course-catalog-demo/state.json")
    clients={key:Client().login(value["Username"],demo["Password"]) for key,value in demo["Users"].items()}
    root=Client().login(os.environ.get("HOJ_TEST_USERNAME","root"),os.environ.get("HOJ_TEST_PASSWORD","hoj123456"))
    assert root.call("/api/edu/me")["isRoot"]
    folder=REPO/"backups/progress-demo"; folder.mkdir(parents=True,exist_ok=True)
    state_file=folder/"state.json"; state=hb.read_json(state_file) if state_file.exists() else {}
    def save(): hb.write_json(state_file,state)
    language=next(item for item in root.call("/api/languages?all=true") if item["name"].startswith("C++"))
    if "ProblemId" not in state:
        public_id="EDU_PROGRESS_OI_01"
        existing=root.call("/api/admin/problem/get-problem-list?currentPage=1&limit=10&keyword="+public_id)["records"]
        if any(row["problemId"]==public_id for row in existing): raise RuntimeError("Progress demo problem exists without matching local state")
        body={"problemId":public_id,"title":"OI练习：输出两倍的数","author":"root","description":"输入一个1至100的整数n，输出2n。两个测试点各50分，用于保留部分正确的真实判题示例。", "input":"一个整数n。","output":"输出2n并换行。", "timeLimit":1000,"memoryLimit":128,"stackLimit":128,"difficulty":0,"auth":1,"type":1,"ioScore":100,"codeShare":True,"examples":"","isRemote":False,"isGroup":False,"hint":"教师进度表的保留演示题。","source":"班级进度表示例","isRemoveEndBlank":False,"openCaseResult":True,"judgeMode":"default","judgeCaseMode":"default","isFileIO":False,"userExtraFile":"","judgeExtraFile":""}
        root.call("/api/admin/problem","POST",{"problem":body,"languages":[language],"tags":[],"codeTemplates":[],"samples":[{"input":"1\n","output":"2\n","score":50,"groupNum":1,"status":0},{"input":"2\n","output":"4\n","score":50,"groupNum":2,"status":0}],"isUploadTestCase":False,"judgeMode":"default","changeModeCode":False,"changeJudgeCaseMode":False})
        rows=root.call("/api/admin/problem/get-problem-list?currentPage=1&limit=10&keyword="+public_id)["records"]
        state.update(ProblemId=int(next(row["id"] for row in rows if row["problemId"]==public_id)),PublicId=public_id); save()
    def submit(key,public_id,code,expected):
        if key not in state:
            if state.get("LastSubmitAt"): time.sleep(max(0,13-(time.time()-state["LastSubmitAt"])))
            item=clients["student"].call("/api/submit-problem-judge","POST",{"pid":public_id,"language":language["name"],"code":"#include <iostream>\nusing namespace std; int main(){"+code+"return 0;}","cid":0,"tid":None,"gid":None,"isRemote":False})
            state[key]=item["submitId"]; state["LastSubmitAt"]=time.time(); save()
        for attempt in range(70):
            submission=clients["student"].call("/api/get-submission-detail?submitId=%s"%state[key])["submission"]
            if submission["status"] not in (5,6,7,9,15):
                assert submission["status"]==expected,(key,submission["status"],expected)
                return
            time.sleep(2)
        raise RuntimeError("Judge timed out for retained demo")
    submit("PartialSubmission",state["PublicId"],"int n;cin>>n;cout<<2<<'\\n';",8)
    submit("WrongAfterPartial",state["PublicId"],"cout<<0<<'\\n';",-1)
    submit("WrongAfterCorrect","EDU_DEMO22_01","cout<<0<<'\\n';",-1)
    klass=demo["Classes"]["0"]; endpoint="/api/edu/classes/%d/progress"%klass
    ids=[demo["Problems"]["1"],demo["Problems"]["3"],state["ProblemId"]]
    for actor in (clients["student"],clients["student2"],clients["outsider"],clients["teacher2"]):
        actor.call(endpoint,"POST",{"problemIds":ids},expected=403)
        actor.call(endpoint+"/problems",expected=403)
    teacher=clients["teacher"]
    for bad in ([],[ids[0],ids[0]],[None],[-1],list(range(1,52))): teacher.call(endpoint,"POST",{"problemIds":bad},expected=400)
    teacher.call(endpoint,"POST",{"problemIds":[999999999]},expected=400)
    teacher.call(endpoint+"/problems?courseId=%d"%demo["Courses"]["3"],expected=400)
    picker=teacher.call(endpoint+"/problems?courseId=%d&limit=50"%demo["Courses"]["0"])
    assert {row["id"] for row in picker["records"]}=={demo["Problems"][str(i)] for i in range(1,6)}
    searched=teacher.call(endpoint+"/problems?keyword="+state["PublicId"])
    assert len(searched["records"])==1 and searched["records"][0]["id"]==state["ProblemId"]
    for actor in (teacher,root):
        result=actor.call(endpoint,"POST",{"problemIds":ids})
        assert [row["id"] for row in result["problems"]]==ids
        assert result["studentCount"]==2
        rows={row["username"]:row for row in result["students"]}
        assert set(rows)=={demo["Users"][key]["Username"] for key in ("student","student2")}
        assert rows[demo["Users"]["student"]["Username"]]["results"]==["CORRECT","WRONG","PARTIAL"]
        assert rows[demo["Users"]["student2"]["Username"]]["results"]==["NOT_SUBMITTED"]*3
        assert not any(key in json.dumps(result).lower() for key in ('"code"','"password"','"submitid"','"ip"'))
    reversed_result=teacher.call(endpoint,"POST",{"problemIds":list(reversed(ids))})
    assert [row["id"] for row in reversed_result["problems"]]==list(reversed(ids))
    current=next(row for row in reversed_result["students"] if row["username"]==demo["Users"]["student"]["Username"])
    assert current["results"]==["PARTIAL","WRONG","CORRECT"]
    try:
        root.call("/api/admin/problem/change-problem-auth","PUT",{"id":state["ProblemId"],"auth":2})
        teacher.call(endpoint,"POST",{"problemIds":ids},expected=400)
        assert not teacher.call(endpoint+"/problems?keyword="+state["PublicId"])["records"]
    finally:
        root.call("/api/admin/problem/change-problem-auth","PUT",{"id":state["ProblemId"],"auth":1})
    assert teacher.call(endpoint,"POST",{"problemIds":ids})["studentCount"]==2
    for table,key in (("user_info","uuid"),("problem","id"),("judge","submit_id")):
        assert before[table].issubset(set(sql("SELECT %s FROM %s;"%(key,table)).splitlines()))
    assert relations=={table:sql("SELECT * FROM %s ORDER BY id;"%table) for table in relations}
    assert len(demo["Problems"])==20 and len(demo["Courses"])==4 and len(demo["Classes"])==3
    result=teacher.call(endpoint,"POST",{"problemIds":ids})
    hb.write_json(REPO/"logs/progress-demo-report.json",result)
    report={"Passed":True,"AllFourFinalStates":True,"PartialIsRealJudgeResult":True,"HistoricalBestSurvivesLaterWrongSubmission":True,"TeacherAndRootOnly":True,"QuestionOrderPreserved":True,"HiddenProblemRejected":True,"AllClassStudentsIncluded":True,"OriginalDataAndRelationsPreserved":True,"RetainedBaseProblems":20,"RetainedAdditionalOIProblem":state["PublicId"],"RetainedBaseCourses":4,"RetainedBaseClasses":3,"ClassId":klass}
    hb.write_json(REPO/"logs/progress-api-acceptance.json",report)
    print("PROGRESS API PASSED: four states, real partial judge, column order, authorization and original data retention")
    print("Retained OI problem: "+state["PublicId"])


if __name__=="__main__":
    os.umask(0o077)
    for stream in (sys.stdout,sys.stderr):
        if hasattr(stream,"reconfigure"): stream.reconfigure(encoding="utf-8")
    try: main()
    except (RuntimeError,OSError,ValueError,KeyError,AssertionError) as error:
        print("PROGRESS API FAILED: "+str(error),file=sys.stderr); sys.exit(1)
