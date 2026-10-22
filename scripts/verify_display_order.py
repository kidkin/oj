#!/usr/bin/env python3
"""Verify editable teaching display numbers using the retained local demo.

Only the Windows Docker Desktop hoj-dev project is accepted. No entities,
memberships or submissions are deleted. Demo display numbers remain available
for manual testing; credentials are read only from the ignored local state.
"""
import hashlib
import json
import os
from pathlib import Path
import sys

import hoj_backup as hb
import restore_dev
from verify_classes import Client

REPO = Path(__file__).resolve().parents[1]


def main():
    docker = hb.Docker()
    restore_dev.local_windows_only(docker)
    mysql = docker.json("container", "inspect", "hoj-dev-hoj-mysql-1")[0]
    assert mysql["Config"]["Labels"]["com.docker.compose.project"] == "hoj-dev"
    state = hb.read_json(REPO / "backups/course-catalog-demo/state.json")
    clients = {key: Client().login(value["Username"], state["Password"]) for key, value in state["Users"].items()}
    root = Client().login(os.environ.get("HOJ_TEST_USERNAME", "root"), os.environ.get("HOJ_TEST_PASSWORD", "hoj123456"))
    assert root.call("/api/edu/me")["isRoot"]

    def sql(query):
        return hb.run([docker.exe, "exec", "-i", mysql["Id"], "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names hoj'], stdin=query).stdout.strip()

    snapshot_queries = {
        "accounts": "SELECT uuid FROM user_info ORDER BY uuid;",
        "problems": "SELECT id FROM problem ORDER BY id;",
        "submissions": "SELECT submit_id FROM judge ORDER BY submit_id;",
        "memberships": "SELECT class_id,uid,role,status FROM edu_class_member ORDER BY class_id,uid;",
        "references": "SELECT class_id,course_id FROM edu_class_course ORDER BY class_id,course_id;",
    }
    before = {key: sql(query) for key, query in snapshot_queries.items()}
    migrations = {}
    for line in sql("SELECT version,checksum FROM edu_schema_history ORDER BY version;").splitlines():
        version, checksum = line.split("\t")
        path = REPO / "hoj-springboot/DataBackup/src/main/resources/db/edu" / (version + ".sql")
        assert hashlib.sha256(path.read_bytes()).hexdigest() == checksum
        migrations[version] = checksum
    assert len(migrations) == 4 and "V004__display_order" in migrations
    classes = [state["Classes"][str(i)] for i in range(3)]
    courses = [state["Courses"][str(i)] for i in range(4)]
    assert len(state["Problems"]) >= 20
    for index, display_id in enumerate([30, 2, 10]):
        owner = clients["teacher2" if index == 2 else "teacher"]
        c = owner.call("/api/edu/classes/%d" % classes[index])
        owner.call("/api/edu/classes/%d" % c["id"], "PUT", {"name": c["name"], "description": c["description"], "status": c["status"], "displayId": display_id})
        assert owner.call("/api/edu/classes/%d" % c["id"])["displayId"] == display_id
    for index, display_id in enumerate([30, 2, 10, 40]):
        owner = clients["teacher2" if index == 3 else "teacher"]
        c = owner.call("/api/edu/courses/%d" % courses[index])
        updated = owner.call("/api/edu/courses/%d" % c["id"], "PUT", {"title": c["title"], "description": c["description"], "status": c["status"], "revision": c["revision"], "displayId": display_id})
        assert updated["id"] == c["id"] and updated["displayId"] == display_id and updated["iconUrl"] == c["iconUrl"]

    student = clients["student"]
    def ordered(records):
        pairs = [(row["displayId"], row["id"]) for row in records]
        assert pairs == sorted(pairs), pairs
    mine = student.call("/api/edu/classes?limit=50")["records"]
    ordered(mine)
    assert [row["id"] for row in mine] == [classes[1], classes[2], classes[0]]
    for scope in ("catalog", "library", "mine"):
        ordered(root.call("/api/edu/courses?scope=%s&limit=50" % scope)["records"])
    for class_id in classes:
        ordered(student.call("/api/edu/classes/%d/courses?limit=50" % class_id)["records"])
    assert not clients["outsider"].call("/api/edu/classes")["records"]
    print("PASS: numeric class/course ordering, shared class rows and membership visibility", flush=True)

    owner = clients["teacher"]
    c = owner.call("/api/edu/courses/%d" % courses[0])
    for actor in (student, clients["outsider"], clients["teacher2"]):
        actor.call("/api/edu/courses/%d" % c["id"], "PUT", {"title": c["title"], "description": c["description"], "status": c["status"], "revision": c["revision"], "displayId": 1}, expected=403)
        actor.call("/api/edu/courses/%d/problems" % c["id"], "PUT", {"problemIds": [], "displayIds": [], "revision": c["revision"]}, expected=403)
    for actor in (student, clients["outsider"]):
        actor.call("/api/edu/classes/%d" % classes[0], "PUT", {"name": "unauthorized", "displayId": 1}, expected=403)
    owner.call("/api/edu/courses/%d" % c["id"], "PUT", {"title": c["title"], "description": c["description"], "status": c["status"], "revision": c["revision"] - 1, "displayId": 99}, expected=409)
    for invalid in (0, -1, 2147483648, 1.5, "abc", "01"):
        owner.call("/api/edu/courses/%d" % c["id"], "PUT", {"title": c["title"], "description": c["description"], "status": c["status"], "revision": c["revision"], "displayId": invalid}, expected=400)
        owner.call("/api/edu/classes/%d" % classes[0], "PUT", {"name": "invalid", "displayId": invalid}, expected=400)
    owner.call("/api/edu/courses", "POST", {"title": "invalid", "displayId": 0}, expected=400)
    owner.call("/api/edu/classes", "POST", {"name": "invalid", "displayId": 0}, expected=400)
    assert owner.call("/api/edu/courses/%d" % c["id"])["revision"] == c["revision"]

    original = {row["pid"]: row for row in c["problems"]}
    pids = [state["Problems"][str(i)] for i in range(1,6)]
    for numbers in ([1,1,3,4,5], [1], [1,None,3,4,5], [1,1.5,3,4,5], [1,0,3,4,5]):
        owner.call("/api/edu/courses/%d/problems" % c["id"], "PUT", {"problemIds": pids, "displayIds": numbers, "revision": c["revision"]}, expected=400)
    assert owner.call("/api/edu/courses/%d" % c["id"])["revision"] == c["revision"]
    updated = owner.call("/api/edu/courses/%d/problems" % c["id"], "PUT", {"problemIds": pids, "displayIds": [30,2,10,40,50], "revision": c["revision"]})
    assert [row["displayId"] for row in updated["problems"]] == [2,10,30,40,50]
    assert [row["pid"] for row in updated["problems"]] == [pids[1],pids[2],pids[0],pids[3],pids[4]]
    assert {row["pid"]: row["problemId"] for row in updated["problems"]} == {pid: row["problemId"] for pid,row in original.items()}
    own = student.call("/api/edu/courses/%d" % c["id"])
    rows = {row["pid"]: row for row in own["problems"]}
    assert rows[pids[0]]["myStatus"] == 0 and rows[pids[0]]["completed"]
    assert rows[pids[2]]["myStatus"] == -1 and not rows[pids[2]]["completed"]
    assert rows[pids[1]]["myStatus"] == -10
    assert all("tags" in row and "ac" in row and "total" in row for row in rows.values())
    shared = student.call("/api/edu/courses/%d" % courses[1])
    reused = next(row for row in shared["problems"] if row["pid"] == pids[0])
    assert reused["displayId"] != rows[pids[0]]["displayId"]
    owner.call("/api/edu/courses/%d/problems" % c["id"], "PUT", {"problemIds": pids, "displayIds": [1,2,3,4,5], "revision": c["revision"]}, expected=409)
    clients["outsider"].call("/api/edu/courses/%d" % c["id"], expected=403)
    print("PASS: per-course numbers, AC/WA/status display, public statistics, conflicts and authorization", flush=True)

    # Older clients omit displayId in metadata; this must preserve custom numbering.
    c = owner.call("/api/edu/courses/%d" % courses[0])
    updated = owner.call("/api/edu/courses/%d" % c["id"], "PUT", {"title": c["title"], "description": c["description"], "status": c["status"], "revision": c["revision"]})
    assert updated["displayId"] == c["displayId"]
    # Same class display ID has a deterministic persistent-ID tie breaker.
    c = owner.call("/api/edu/classes/%d" % classes[0])
    for number in (2,30):
        owner.call("/api/edu/classes/%d" % c["id"], "PUT", {"name": c["name"], "description": c["description"], "status": c["status"], "displayId": number})
        ordered(student.call("/api/edu/classes?limit=50")["records"])
    after = {key: sql(query) for key, query in snapshot_queries.items()}
    assert before == after, "An existing account/problem/submission/membership/reference changed"
    for table,key in (("user_info","uuid"),("problem","id"),("judge","submit_id")):
        assert set(state["OriginalIds"][table]).issubset(sql("SELECT %s FROM %s;" % (key,table)).splitlines())
    classes_now = student.call("/api/edu/classes?limit=50")["records"]
    first = next(row for row in classes_now if row["id"] == classes[0])
    assert (first["completedCount"],first["problemCount"]) == (2,10)
    report = {"Passed":True, "NumericOrdering":True, "SharedCourseOrdering":True, "PerCourseQuestionNumbers":True,
              "InvalidInputAndPermissionGuards":True, "ACWAStatusAndProgressPreserved":True, "OriginalDataPreserved":True,
              "RetainedProblems":len(state["Problems"]), "RetainedCourses":len(courses), "RetainedClasses":len(classes),
              "MigrationVersions":list(migrations), "ClassDisplayIds":[30,2,10], "CourseDisplayIds":[30,2,10,40],
              "FirstCourseQuestionDisplayIds":[2,10,30,40,50]}
    hb.write_json(REPO / "logs/display-order-acceptance.json",report)
    print("DISPLAY ORDER CHECK PASSED; all demo and original data retained.")


if __name__ == "__main__":
    os.umask(0o077)
    for stream in (sys.stdout,sys.stderr):
        if hasattr(stream,"reconfigure"): stream.reconfigure(encoding="utf-8")
    try: main()
    except (RuntimeError,OSError,ValueError,AssertionError,KeyError) as error:
        print("DISPLAY ORDER CHECK FAILED: " + str(error),file=sys.stderr)
        sys.exit(1)
