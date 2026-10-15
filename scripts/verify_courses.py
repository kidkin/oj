#!/usr/bin/env python3
"""Exercise shared courses on local hoj-dev; remove only this run's fixtures."""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sys
from urllib.parse import quote

import hoj_backup as hb
import restore_dev
from verify_classes import Client


def main():
    docker = hb.Docker()
    restore_dev.local_windows_only(docker)
    info = docker.json("container", "inspect", "hoj-dev-hoj-mysql-1")[0]
    if info["Config"]["Labels"].get("com.docker.compose.project") != "hoj-dev":
        raise RuntimeError("Only the local development project is allowed")

    def sql(statement):
        return hb.run([docker.exe, "exec", "-i", info["Id"], "sh", "-c",
                       'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names hoj'],
                      stdin=statement).stdout.strip()

    def originals():
        return {table: set(sql("SELECT %s FROM %s;" % (key, table)).splitlines())
                for table, key in (("user_info", "uuid"), ("problem", "id"), ("judge", "submit_id"))}

    before = originals()
    root = Client().login(os.environ.get("HOJ_TEST_USERNAME", "root"), os.environ.get("HOJ_TEST_PASSWORD", "hoj123456"))
    assert root.call("/api/edu/me")["isRoot"]
    prefix = "c" + secrets.token_hex(4)
    names = {key: prefix + "_" + key for key in ("t1", "t2", "s1", "s2", "s3")}
    password = secrets.token_hex(12)
    uids, class_ids, course_ids, problem_ids, checks = {}, [], [], [], []

    def passed(name):
        checks.append(name)
        print("PASS: " + name, flush=True)

    def make_class(client, suffix):
        row = client.call("/api/edu/classes", "POST", {"name": prefix + suffix, "description": "Disposable course test"})
        class_ids.append(int(row["id"]))
        return int(row["id"])

    def make_course(client, suffix):
        row = client.call("/api/edu/courses", "POST", {"title": prefix + suffix, "description": "Disposable course test", "status": 1, "ownerUid": "forged"})
        course_ids.append(int(row["id"]))
        return row

    def get(client, course_id):
        return client.call("/api/edu/courses/%s" % course_id)

    def metadata(client, row, status=1, title=None, expected=200):
        return client.call("/api/edu/courses/%s" % row["id"], "PUT", {
            "title": title or row["title"], "description": row["description"], "status": status, "revision": row["revision"]}, expected)

    def questions(client, row, ids, expected=200):
        return client.call("/api/edu/courses/%s/problems" % row["id"], "PUT", {"problemIds": ids, "revision": row["revision"]}, expected)

    def attach(client, class_id, ids, expected=200):
        return client.call("/api/edu/classes/%s/courses" % class_id, "POST", {"courseIds": ids}, expected)

    def detach(client, class_id, course_id):
        client.call("/api/edu/classes/%s/courses/%s" % (class_id, course_id), "DELETE")

    def enroll(class_id, key):
        root.call("/api/edu/classes/%s/members" % class_id, "POST", {"usernames": [names[key]], "role": "STUDENT"})

    def leave(class_id, key):
        root.call("/api/edu/classes/%s/members/%s" % (class_id, uids[key]), "DELETE")

    def change_auth(pid, auth):
        root.call("/api/admin/problem/change-problem-auth", "PUT", {"id": pid, "auth": auth})

    try:
        root.call("/api/admin/user/insert-batch-user", "POST", {"users": [[name, password, ""] for name in names.values()]})
        for key, name in names.items():
            rows = root.call("/api/admin/user/get-user-list?currentPage=1&limit=10&keyword=" + name)["records"]
            uid = next(row["uid"] for row in rows if row["username"] == name)
            if not re.fullmatch(r"[a-fA-F0-9]{32}", uid):
                raise RuntimeError("Unexpected fixture UUID")
            uids[key] = uid
        clients = {key: Client().login(name, password) for key, name in names.items()}
        for key in ("t1", "t2"):
            root.call("/api/edu/teachers", "POST", {"username": names[key]})
        t1, t2, s1, s2, s3 = [clients[key] for key in ("t1", "t2", "s1", "s2", "s3")]
        Client().call("/api/edu/courses", expected=401)
        s1.call("/api/edu/courses", "POST", {"title": "forbidden"}, 403)
        s1.call("/api/edu/public-problems", expected=403)
        passed("anonymous and student accounts cannot create courses or use the teacher problem picker")

        a, b, other = make_class(t1, "A"), make_class(t2, "B"), make_class(t2, "C")
        for class_id, key in ((a, "s1"), (a, "s2"), (b, "s1"), (b, "s3")):
            enroll(class_id, key)
        c, draft = make_course(t1, "Shared"), make_course(t1, "Draft")
        cid = c["id"]
        assert c["ownerUid"] == uids["t1"] and c["status"] == 0
        t2.call("/api/edu/courses/%s" % cid, expected=403)
        s1.call("/api/edu/courses/%s" % cid, expected=403)
        passed("course ownership derives from login and new courses remain private drafts")

        language = next(item for item in root.call("/api/languages?all=true") if item["name"].startswith("C++"))
        for index in range(3):
            problem_name = prefix.upper() + "_P" + str(index)
            body = {"problemId": problem_name, "title": problem_name, "author": "root", "description": "Disposable A+B fixture",
                    "input": "Two integers", "output": "Their sum", "timeLimit": 1000, "memoryLimit": 128, "stackLimit": 128,
                    "difficulty": 0, "auth": 2 if index == 2 else 1, "codeShare": True, "examples": "", "isRemote": False,
                    "isGroup": False, "type": 0, "hint": "", "source": "Local course verification", "isRemoveEndBlank": False,
                    "openCaseResult": True, "judgeMode": "default", "judgeCaseMode": "default", "isFileIO": False,
                    "userExtraFile": "", "judgeExtraFile": ""}
            root.call("/api/admin/problem", "POST", {"problem": body, "languages": [language], "tags": [], "codeTemplates": [],
                      "samples": [{"input": "1 2\n", "output": "3\n", "score": 0, "groupNum": 1, "status": 0}],
                      "isUploadTestCase": False, "judgeMode": "default", "changeModeCode": False, "changeJudgeCaseMode": False})
            rows = root.call("/api/admin/problem/get-problem-list?currentPage=1&limit=10&keyword=" + problem_name)["records"]
            problem_ids.append(int(next(row["id"] for row in rows if row["problemId"] == problem_name)))
        p1, p2, hidden = problem_ids
        picker = t1.call("/api/edu/public-problems?keyword=" + prefix.upper())["records"]
        assert {row["id"] for row in picker} == {p1, p2}
        c = questions(t1, c, [p1, p2])
        for bad in ([p1, p1], [p1, hidden], [p1, 9223372036854775807], list(range(1, 502))):
            questions(t1, c, bad, 400)
            assert [row["pid"] for row in get(t1, cid)["problems"]] == [p1, p2]
        passed("problem selection excludes hidden problems; invalid batches preserve the complete old order")

        c = metadata(t1, c)
        assert not get(t2, cid)["canEdit"]
        metadata(t2, c, expected=403)
        questions(t2, c, [p2], 403)
        t1.call("/api/edu/classes/%s/courses" % b, expected=403)
        attach(t1, b, [cid], 403)
        s1.call("/api/edu/courses/%s" % cid, expected=403)
        passed("published courses can be reused by teachers without granting editing or unrelated class access")

        attach(t1, a, [cid, draft["id"]], 400)
        assert not root.call("/api/edu/classes/%s/courses" % a)["records"]
        attach(t1, a, [cid, cid])
        attach(t1, a, [cid])
        attach(t2, b, [cid])
        assert int(sql("SELECT COUNT(*) FROM edu_class_course WHERE course_id=%d;" % cid)) == 2
        assert len(s1.call("/api/edu/courses?scope=mine")["records"]) == 1
        mine = get(s1, cid)
        assert {item["id"] for item in mine["sourceClasses"]} == {a, b}
        assert "referenceCount" not in mine and not mine["canEdit"]
        assert {item["id"] for item in get(s2, cid)["sourceClasses"]} == {a}
        s2.call("/api/edu/classes/%s/courses" % b, expected=403)
        s1.call("/api/edu/classes/%s/courses" % other, expected=403)
        passed("class references are atomic and idempotent; multi-class student courses are deduplicated with private source lists")

        stale = dict(c)
        c = metadata(t1, c, title=prefix + "Updated")
        questions(t1, stale, [p2], 409)
        metadata(t1, stale, title="stale overwrite", expected=409)
        c = questions(t1, c, [p2, p1])
        for client, class_id in ((s2, a), (s3, b)):
            rows = client.call("/api/edu/classes/%s/courses" % class_id)["records"]
            assert rows[0]["title"] == c["title"] and rows[0]["revision"] == c["revision"]
            assert [row["pid"] for row in get(client, cid)["problems"]] == [p2, p1]
        passed("title and question order update across both classes; stale revisions cannot overwrite a newer version")

        metadata(t1, c, status=0, expected=400)
        metadata(t1, c, status=2, expected=400)
        change_auth(p2, 2)
        student = get(s1, cid)
        assert [row["pid"] for row in student["problems"]] == [p1] and student["problemCount"] == 1
        editor = get(t1, cid)
        masked = next(row for row in editor["problems"] if row["pid"] == p2)
        assert editor["unavailableCount"] == 1 and masked["problemId"] is None and masked["title"] == "题目已不可用"
        assert p2 not in {row["id"] for row in t1.call("/api/edu/public-problems?keyword=" + prefix.upper())["records"]}
        change_auth(p2, 1)
        # Simulate a legacy team-only flag on THIS run's problem, without changing any real problem.
        sql("UPDATE problem SET is_group=1 WHERE id=%d;" % p2)
        assert [row["pid"] for row in get(s1, cid)["problems"]] == [p1]
        sql("UPDATE problem SET is_group=0 WHERE id=%d;" % p2)
        assert get(s1, cid)["problemCount"] == 2
        passed("referenced courses cannot be unpublished; current hidden and team problem permissions take effect immediately")

        old_ac = sql("SELECT DISTINCT j.pid FROM judge j JOIN problem p ON p.id=j.pid JOIN user_info u ON u.uuid=j.uid "
                     "WHERE u.username='root' AND j.status=0 AND j.cid=0 AND j.gid IS NULL AND p.auth=1 AND p.is_group=0 AND p.gid IS NULL LIMIT 1;")
        if not old_ac:
            raise RuntimeError("Run Verify-Dev.ps1 once to establish the historical ordinary AC fixture")
        c = questions(t1, c, [int(old_ac)])
        assert get(root, cid)["completedCount"] == 1
        assert get(s1, cid)["completedCount"] == 0
        c = questions(t1, c, [p2, p1])
        root.call("/api/admin/problem?pid=%s" % p2, "DELETE")
        problem_ids.remove(p2)
        c = get(t1, cid)
        assert [row["pid"] for row in c["problems"]] == [p1]
        assert set(before["judge"]).issubset(originals()["judge"])
        passed("own historical ordinary AC is reused without leaking other users' progress; legacy problem deletion removes only course references")

        detach(t1, a, cid)
        s2.call("/api/edu/courses/%s" % cid, expected=403)
        assert get(s1, cid)["id"] == cid and len(s1.call("/api/edu/courses")["records"]) == 1
        assert len(s3.call("/api/edu/classes/%s/courses" % b)["records"]) == 1
        attach(t1, a, [cid])
        leave(a, "s1")
        assert get(s1, cid)["id"] == cid
        leave(b, "s1")
        s1.call("/api/edu/courses/%s" % cid, expected=403)
        assert not s1.call("/api/edu/courses?scope=library")["records"]
        assert not s1.call("/api/edu/courses?scope=mine&publishedOnly=false")["records"]
        passed("removing one reference or membership preserves the other class; losing all memberships revokes every course read route")

        root.call("/api/edu/classes/%s" % a, "PUT", {"name": prefix + "A", "description": "", "status": 1})
        s2.call("/api/edu/courses/%s" % cid, expected=403)
        attach(t1, a, [cid], 400)
        detach(t1, a, cid)
        assert get(s3, cid)["id"] == cid
        detach(t2, b, cid)
        c = metadata(t1, c, status=2)
        t2.call("/api/edu/courses/%s" % cid, expected=403)
        root.call("/api/edu/teachers/" + uids["t1"], "DELETE")
        questions(t1, c, [], 403)
        metadata(t1, c, status=1, expected=403)
        passed("archival and teacher revocation apply to existing sessions; archived class references can still be removed safely")

        migrations = dict(line.split("\t") for line in sql("SELECT version,checksum FROM edu_schema_history;").splitlines())
        directory = Path(__file__).resolve().parents[1] / "hoj-springboot/DataBackup/src/main/resources/db/edu"
        for path in directory.glob("V*.sql"):
            assert migrations[path.stem] == hashlib.sha256(path.read_bytes()).hexdigest()
        assert migrations["V001__class_members"] == "5054222d7b2e75df3f8d7e216fa171f40e480e958054758d791f5d3ee6c63044"
        passed("incremental schema versions have matching checksums and preserve the original V001 migration")
    finally:
        owners = ",".join("'%s'" % uid for uid in uids.values())
        for table, values in (("edu_course", course_ids), ("edu_class", class_ids)):
            if values:
                ids = ",".join(map(str, values))
                found = set(map(int, sql("SELECT id FROM %s WHERE id IN (%s) AND owner_uid IN (%s);" % (table, ids, owners)).splitlines()))
                if found != set(values):
                    raise RuntimeError("Fixture ownership check failed; cleanup stopped")
        if class_ids:
            ids = ",".join(map(str, class_ids))
            sql("DELETE FROM edu_class_course WHERE class_id IN (%s); DELETE FROM edu_class_member WHERE class_id IN (%s); DELETE FROM edu_class WHERE id IN (%s);" % (ids, ids, ids))
        if course_ids:
            ids = ",".join(map(str, course_ids))
            sql("DELETE FROM edu_course_problem WHERE course_id IN (%s); DELETE FROM edu_course WHERE id IN (%s);" % (ids, ids))
        for pid in problem_ids:
            current = sql("SELECT problem_id FROM problem WHERE id=%d;" % pid)
            if not current.startswith(prefix.upper() + "_P"):
                raise RuntimeError("Problem fixture identity mismatch; cleanup stopped")
            root.call("/api/admin/problem?pid=%s" % pid, "DELETE")
        if uids:
            root.call("/api/admin/user/delete-user", "DELETE", {"ids": list(uids.values())})
    assert originals() == before, "Pre-existing users, problems or submission IDs changed"
    passed("all disposable fixtures were removed and pre-existing account, problem and submission IDs are unchanged")
    target = Path(__file__).resolve().parents[1] / "logs/courses-acceptance.json"
    target.write_text(json.dumps({"Passed": True, "Checks": checks, "FixtureCleanup": True,
                                 "OriginalCounts": {key: len(value) for key, value in before.items()}}, indent=2) + "\n", encoding="utf-8")
    print("COURSE ACCEPTANCE PASSED: " + str(target))


if __name__ == "__main__":
    os.umask(0o077)
    try:
        main()
    except (RuntimeError, OSError, ValueError, AssertionError) as error:
        print("COURSE ACCEPTANCE FAILED: " + str(error), file=sys.stderr)
        sys.exit(1)
