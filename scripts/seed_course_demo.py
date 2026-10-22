#!/usr/bin/env python3
"""Create and retain a local teaching demo: 20 problems, four courses and three classes.

Only Windows Docker Desktop's hoj-dev project is accepted. Credentials/state are
written to ignored backups/course-catalog-demo/, never into Git. Re-running resumes
this named fixture; it does not reset or delete an existing course or class.
"""
import json
import os
from pathlib import Path
import re
import secrets
import sys
import time
import urllib.error
import urllib.request

import hoj_backup as hb
import restore_dev
from verify_classes import Client


REPO = Path(__file__).resolve().parents[1]
STATE_DIR = REPO / "backups/course-catalog-demo"
PROBLEMS = [
    ("两数之和", "读入两个整数，输出它们的和。", [("1 2\n", "3\n"), ("-3 5\n", "2\n")]),
    ("两数之差", "读入两个整数a、b，输出a-b。", [("5 2\n", "3\n"), ("-1 4\n", "-5\n")]),
    ("两数之积", "读入两个整数，输出它们的乘积。", [("3 4\n", "12\n"), ("-2 5\n", "-10\n")]),
    ("商与余数", "读入非负整数a与正整数b，输出a/b的整数商与余数，以空格分隔。", [("17 5\n", "3 2\n"), ("8 4\n", "2 0\n")]),
    ("长方形面积", "读入长和宽两个正整数，输出长方形面积。", [("3 7\n", "21\n"), ("1 8\n", "8\n")]),
    ("较大的数", "读入两个整数，输出较大的一个。", [("4 9\n", "9\n"), ("-2 -7\n", "-2\n")]),
    ("绝对值", "读入一个范围在-10000至10000的整数，输出其绝对值。", [("-17\n", "17\n"), ("0\n", "0\n")]),
    ("奇偶判断", "读入一个整数，偶数输出even，奇数输出odd。", [("8\n", "even\n"), ("7\n", "odd\n")]),
    ("三个数的最大值", "读入三个整数，输出其中最大值。", [("2 9 4\n", "9\n"), ("-3 -1 -8\n", "-1\n")]),
    ("成绩等级", "读入0至100的成绩，90分及以上输出A，60至89输出B，其余输出C。", [("95\n", "A\n"), ("72\n", "B\n"), ("40\n", "C\n")]),
    ("从1加到n", "读入1至1000的整数n，输出1到n的和。", [("5\n", "15\n"), ("10\n", "55\n")]),
    ("阶乘", "读入0至12的整数n，输出n的阶乘。", [("5\n", "120\n"), ("0\n", "1\n")]),
    ("统计偶数", "读入非负整数n，输出1至n中偶数的个数。", [("9\n", "4\n"), ("10\n", "5\n")]),
    ("各位数字之和", "读入非负整数n，输出十进制各位数字之和。", [("2026\n", "10\n"), ("0\n", "0\n")]),
    ("整数反转", "读入非负整数n，逆序其十进制数字并作为整数输出。", [("120\n", "21\n"), ("907\n", "709\n")]),
    ("数组最大值", "先读入n，再读入n个整数，输出最大值。1≤n≤100。", [("5\n1 8 3 9 2\n", "9\n"), ("3\n-5 -2 -7\n", "-2\n")]),
    ("数组求和", "先读入n，再读入n个整数，输出它们的和。1≤n≤100。", [("4\n1 2 3 4\n", "10\n"), ("3\n-1 0 2\n", "1\n")]),
    ("不同整数的数量", "先读入n，再读入n个整数，输出不同整数的个数。1≤n≤100。", [("5\n1 2 1 3 2\n", "3\n"), ("3\n7 7 7\n", "1\n")]),
    ("查找目标", "读入n、目标x，再读入n个整数；输出x第一次出现的下标（从1开始），不存在输出-1。", [("4 8\n3 8 8 2\n", "2\n"), ("3 4\n1 2 3\n", "-1\n")]),
    ("字符串长度", "读入不含空格的ASCII字符串，输出长度。", [("hello\n", "5\n"), ("HOJ2026\n", "7\n")]),
]


def upload(client, course_id, revision, payload, filename="icon.png", mime="image/png", expected=200):
    boundary = "----hoj" + secrets.token_hex(12)
    body = ("--%s\r\nContent-Disposition: form-data; name=\"revision\"\r\n\r\n%s\r\n" % (boundary, revision)).encode()
    body += ("--%s\r\nContent-Disposition: form-data; name=\"image\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n" % (boundary, filename, mime)).encode()
    body += payload + ("\r\n--%s--\r\n" % boundary).encode()
    request = urllib.request.Request("http://127.0.0.1:18081/api/edu/courses/%d/icon" % course_id, body,
                                    {"Content-Type": "multipart/form-data; boundary=" + boundary, "Authorization": client.token, "Url-Type": "general"})
    try:
        response = client.opener.open(request, timeout=40)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        result = json.load(response)
    if result.get("status") != expected:
        raise RuntimeError("Icon upload returned %s, wanted %s" % (result.get("status"), expected))
    return result.get("data")


def main():
    docker = hb.Docker(); restore_dev.local_windows_only(docker)
    mysql = docker.json("container", "inspect", "hoj-dev-hoj-mysql-1")[0]
    if mysql["Config"]["Labels"].get("com.docker.compose.project") != "hoj-dev":
        raise RuntimeError("Only the local development project is allowed")
    def sql(query):
        return hb.run([docker.exe, "exec", "-i", mysql["Id"], "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names hoj'], stdin=query).stdout.strip()
    root = Client().login(os.environ.get("HOJ_TEST_USERNAME", "root"), os.environ.get("HOJ_TEST_PASSWORD", "hoj123456"))
    assert root.call("/api/edu/me")["isRoot"]
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    state_path = STATE_DIR / "state.json"
    if state_path.exists():
        state = hb.read_json(state_path)
    else:
        state = {"Password": secrets.token_hex(8), "Users": {}, "Problems": {}, "Courses": {}, "Classes": {}, "Submissions": {},
                 "OriginalIds": {table: sql("SELECT %s FROM %s;" % (key, table)).splitlines() for table, key in (("user_info", "uuid"), ("problem", "id"), ("judge", "submit_id"))}}
    def save(): hb.write_json(state_path, state)
    save()
    keys = ["teacher", "teacher2", "student", "student2", "outsider"]
    for key in keys:
        username = "demo22_" + key
        if key not in state["Users"]:
            existing = root.call("/api/admin/user/get-user-list?currentPage=1&limit=10&keyword=" + username)["records"]
            if any(row["username"] == username for row in existing):
                raise RuntimeError("Demo account already exists without matching state: " + username)
            root.call("/api/admin/user/insert-batch-user", "POST", {"users": [[username, state["Password"], ""]]})
            rows = root.call("/api/admin/user/get-user-list?currentPage=1&limit=10&keyword=" + username)["records"]
            uid = next(row["uid"] for row in rows if row["username"] == username)
            assert re.fullmatch(r"[a-fA-F0-9]{32}", uid)
            state["Users"][key] = {"Username": username, "Uid": uid}; save()
    clients = {key: Client().login(state["Users"][key]["Username"], state["Password"]) for key in keys}
    for key in ("teacher", "teacher2"):
        root.call("/api/edu/teachers", "POST", {"username": state["Users"][key]["Username"]})
    (STATE_DIR / "accounts.txt").write_text("Local demo at http://127.0.0.1:18081\n" +
        "\n".join("%s: %s" % (key, state["Users"][key]["Username"]) for key in keys) + "\nShared demo password: " + state["Password"] + "\n", encoding="utf-8")
    language = next(item for item in root.call("/api/languages?all=true") if item["name"].startswith("C++"))
    for index, (title, description, samples) in enumerate(PROBLEMS, 1):
        key = str(index); public_id = "EDU_DEMO22_%02d" % index
        if key not in state["Problems"]:
            rows = root.call("/api/admin/problem/get-problem-list?currentPage=1&limit=10&keyword=" + public_id)["records"]
            if any(row["problemId"] == public_id for row in rows): raise RuntimeError("Demo problem ID exists without state")
            body = {"problemId": public_id, "title": title, "author": "root", "description": description,
                    "input": "按题目描述读入。所有运算结果在32位有符号整数范围内。", "output": "按题目描述输出，末尾换行。",
                    "timeLimit": 1000, "memoryLimit": 128, "stackLimit": 128, "difficulty": 0, "auth": 1,
                    "codeShare": True, "examples": "", "isRemote": False, "isGroup": False, "type": 0,
                    "hint": "保留的本地教学演示题", "source": "HOJ课程演示", "isRemoveEndBlank": False, "openCaseResult": True,
                    "judgeMode": "default", "judgeCaseMode": "default", "isFileIO": False, "userExtraFile": "", "judgeExtraFile": ""}
            root.call("/api/admin/problem", "POST", {"problem": body, "languages": [language], "tags": [], "codeTemplates": [],
                "samples": [{"input": x, "output": y, "score": 0, "groupNum": 1, "status": 0} for x,y in samples],
                "isUploadTestCase": False, "judgeMode": "default", "changeModeCode": False, "changeJudgeCaseMode": False})
            rows = root.call("/api/admin/problem/get-problem-list?currentPage=1&limit=10&keyword=" + public_id)["records"]
            state["Problems"][key] = int(next(row["id"] for row in rows if row["problemId"] == public_id)); save()
            print("Created demo problem %d/20" % index, flush=True)
    titles = ["01 · 输入输出与运算", "02 · 条件分支", "03 · 循环与数字", "04 · 数组与字符串"]
    for index, title in enumerate(titles):
        key = str(index); owner = clients["teacher2" if index == 3 else "teacher"]
        if key not in state["Courses"]:
            c = owner.call("/api/edu/courses", "POST", {"title": "演示课程 · " + title, "description": "通过真实题目练习C++基础。保留的教学演示课程，可自行上传图标并体验共享更新。"})
            state["Courses"][key] = c["id"]; save()
            ids = [state["Problems"][str(value)] for value in range(index * 5 + 1, index * 5 + 6)]
            if index == 1: ids.insert(0, state["Problems"]["1"])
            c = owner.call("/api/edu/courses/%d/problems" % c["id"], "PUT", {"problemIds": ids, "revision": c["revision"]})
            c = owner.call("/api/edu/courses/%d" % c["id"], "PUT", {"title": c["title"], "description": c["description"], "status": 1, "revision": c["revision"]})
            assets = ["logo.png", "icon.png", "libre-logo.png", "hdu-logo.png"]
            upload(owner, c["id"], c["revision"], (REPO / "hoj-vue/src/assets" / assets[index]).read_bytes())
    for index, name in enumerate(["C++启航 · 基础一班", "算法进阶 · 提高二班", "综合练习 · 实验三班"]):
        key = str(index); owner = clients["teacher2" if index == 2 else "teacher"]
        if key not in state["Classes"]:
            c = owner.call("/api/edu/classes", "POST", {"name": "演示班级 · " + name, "description": "保留的页面与教学流程演示班级。多个班级共享课程，学习完成情况按本人历史AC统计。"})
            state["Classes"][key] = c["id"]; save()
            students = [state["Users"]["student"]["Username"]]
            if index == 0: students.append(state["Users"]["student2"]["Username"])
            owner.call("/api/edu/classes/%d/members" % c["id"], "POST", {"usernames": students, "role": "STUDENT"})
            selected = ([0,1], [1,2], [0,2,3])[index]
            owner.call("/api/edu/classes/%d/courses" % c["id"], "POST", {"courseIds": [state["Courses"][str(i)] for i in selected]})
    # Verify catalog metadata and detail permissions without deleting the retained demo.
    outsider = clients["outsider"]; student = clients["student"]
    catalog = outsider.call("/api/edu/courses?scope=catalog&limit=50")["records"]
    ours = [row for row in catalog if row["id"] in state["Courses"].values()]
    assert len(ours) == 4 and all(not row["canAccess"] and not row["canEdit"] for row in ours)
    assert all("problems" not in row and "completedCount" not in row and not row["sourceClasses"] and "referenceCount" not in row for row in ours)
    cid = state["Courses"]["0"]
    outsider.call("/api/edu/courses/%d" % cid, expected=403)
    assert get_course(student, cid)["canAccess"]
    c = get_course(clients["teacher"], cid)
    payload = (REPO / "hoj-vue/src/assets/icon.png").read_bytes()
    for client in (student, outsider, clients["teacher2"]): upload(client, cid, c["revision"], payload, expected=403)
    upload(clients["teacher"], cid, c["revision"] - 1, payload, expected=409)
    upload(clients["teacher"], cid, c["revision"], b"<svg/>", expected=400)
    upload(clients["teacher"], cid, c["revision"], b"x" * (2 * 1024 * 1024 + 1), expected=400)
    assert get_course(student, cid)["iconUrl"] == c["iconUrl"]
    with student.opener.open("http://127.0.0.1:18081" + c["iconUrl"]) as response:
        assert response.headers.get("Content-Type", "").startswith("image/png")
        assert response.read(8) == b"\x89PNG\r\n\x1a\n"
    print("PASS: catalog is metadata-only; detail, icon ownership, size, content and stale-revision checks passed", flush=True)
    # Preserve real AC/WA records as part of the requested example data.
    programs = {"1": "int a,b;cin>>a>>b;cout<<a+b;", "6": "int a,b;cin>>a>>b;cout<<max(a,b);", "3": "cout<<0;"}
    for number, body in programs.items():
        if number in state["Submissions"]: continue
        if state["Submissions"]: time.sleep(12)
        code = "#include <bits/stdc++.h>\nusing namespace std;int main(){" + body + "cout<<'\\n';return 0;}"
        item = student.call("/api/submit-problem-judge", "POST", {"pid": "EDU_DEMO22_%02d" % int(number), "language": language["name"], "code": code, "cid": 0, "tid": None, "gid": None, "isRemote": False})
        state["Submissions"][number] = item["submitId"]; save()
        for attempt in range(60):
            submission = student.call("/api/get-submission-detail?submitId=%s" % item["submitId"])["submission"]
            if submission["status"] not in (5,6,7,9,15):
                assert submission["status"] == (-1 if number == "3" else 0)
                print("PASS demo submission: " + ("WA" if number == "3" else "AC"), flush=True); break
            time.sleep(2)
        else: raise RuntimeError("Demo judge timed out")
    mine = student.call("/api/edu/classes?limit=50")["records"]
    assert {row["id"] for row in mine} == set(state["Classes"].values())
    assert len(outsider.call("/api/edu/classes")["records"]) == 0
    first = student.call("/api/edu/classes/%d/courses" % state["Classes"]["0"])["records"]
    by_course = {row["id"]: row for row in first}
    assert [(by_course[state["Courses"][str(i)]]["problemCount"],by_course[state["Courses"][str(i)]]["completedCount"]) for i in range(2)] == [(5,1),(6,2)]
    class_first = next(row for row in mine if row["id"] == state["Classes"]["0"])
    assert (class_first["problemCount"], class_first["completedCount"]) == (10,2)
    for table,key in (("user_info","uuid"),("problem","id"),("judge","submit_id")):
        assert set(state["OriginalIds"][table]).issubset(sql("SELECT %s FROM %s;" % (key,table)).splitlines())
    report = {"Passed":True,"Retained":True,"Problems":20,"Courses":4,"Classes":3,"Accounts":5,
              "CatalogMetadataOnly":True,"UnenrolledDetailDenied":True,"UploadGuardsPassed":True,"OriginalIdsPreserved":True,
              "ClassProgressDeduplicated":True,"Submissions":state["Submissions"],"StateDirectory":str(STATE_DIR)}
    hb.write_json(REPO / "logs/course-catalog-acceptance.json", report)
    print("RETAINED DEMO READY: 20 problems, 4 courses, 3 classes")
    print("Demo accounts: " + str(STATE_DIR / "accounts.txt"))


def get_course(client, course_id): return client.call("/api/edu/courses/%d" % course_id)


if __name__ == "__main__":
    os.umask(0o077)
    try: main()
    except (RuntimeError, OSError, ValueError, AssertionError) as error:
        print("DEMO CHECK FAILED: " + str(error), file=sys.stderr); sys.exit(1)
