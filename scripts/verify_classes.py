#!/usr/bin/env python3
"""Local hoj-dev integration test. Creates disposable users/classes, then removes only its own fixtures."""
import argparse
import json
import os
import re
from pathlib import Path
import secrets
import sys
import time
import urllib.error
import urllib.request

import hoj_backup as hb
import restore_dev


class Client:
    def __init__(self):
        self.token = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, path, method="GET", data=None, expected=200):
        headers = {"Url-Type": "admin" if path.startswith("/api/admin/") else "general"}
        if self.token:
            headers["Authorization"] = self.token
        body = None if data is None else json.dumps(data).encode("utf-8")
        if body:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request("http://127.0.0.1:18081" + path, body, headers, method=method)
        try:
            response = self.opener.open(req, timeout=40)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = json.load(response)
            if response.headers.get("Authorization"):
                self.token = response.headers["Authorization"]
        if payload.get("status") != expected:
            # Do not include request bodies, tokens or returned user records.
            raise RuntimeError("Unexpected status at %s: %s (wanted %s)" % (path, payload.get("status"), expected))
        return payload.get("data")

    def login(self, username, password):
        self.call("/api/login", "POST", {"username": username, "password": password})
        if not self.token:
            raise RuntimeError("Login did not return a token")
        return self


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--username", default="root")
    parser.add_argument("--password", default=None, help="Prefer HOJ_TEST_PASSWORD environment variable")
    args = parser.parse_args()
    docker = hb.Docker()
    restore_dev.local_windows_only(docker)
    info = docker.json("container", "inspect", "hoj-dev-hoj-mysql-1")[0]
    if info["Config"]["Labels"].get("com.docker.compose.project") != "hoj-dev":
        raise RuntimeError("Only the local development project is allowed")

    def sql(text):
        return hb.run([docker.exe, "exec", "-i", info["Id"], "sh", "-c",
                       'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names hoj'],
                      stdin=text).stdout.strip()

    def original_ids():
        return {table: set(sql("SELECT %s FROM %s;" % (key, table)).splitlines())
                for table, key in (("user_info", "uuid"), ("problem", "id"), ("judge", "submit_id"))}

    baseline = original_ids()
    root = Client().login(args.username, args.password or os.environ.get("HOJ_TEST_PASSWORD", "hoj123456"))
    if not root.call("/api/edu/me")["isRoot"]:
        raise RuntimeError("A local super administrator is required for disposable fixtures")
    prefix = "e" + secrets.token_hex(3)
    names = {key: prefix + "_" + key for key in ("t1", "t2", "s1", "s2", "s3")}
    password = secrets.token_hex(8)
    uids, classes, checks = {}, [], []

    def passed(name):
        checks.append(name)
        print("PASS: " + name, flush=True)

    def create(client, suffix):
        row = client.call("/api/edu/classes", "POST", {"name": prefix + suffix, "description": "Disposable integration fixture", "ownerUid": "forged-owner"})
        classes.append(int(row["id"]))
        return row

    def enroll(class_id, keys, role="STUDENT", expected=200):
        root.call("/api/edu/classes/%d/members" % class_id, "POST", {"usernames": [names[key] for key in keys], "role": role}, expected)

    try:
        root.call("/api/admin/user/insert-batch-user", "POST", {"users": [[name, password, ""] for name in names.values()]})
        for key, name in names.items():
            rows = root.call("/api/admin/user/get-user-list?currentPage=1&limit=10&keyword=" + name)["records"]
            candidate = next(row["uid"] for row in rows if row["username"] == name)
            if not re.fullmatch(r"[a-fA-F0-9]{32}", candidate):
                raise RuntimeError("Unexpected fixture UUID; cleanup requires review")
            uids[key] = candidate
        clients = {key: Client().login(name, password) for key, name in names.items()}
        for key in ("t1", "t2"):
            root.call("/api/edu/teachers", "POST", {"username": names[key]})
            root.call("/api/edu/teachers", "POST", {"username": names[key]})
            assert clients[key].call("/api/edu/me")["isTeacher"]
        passed("teacher grants are idempotent and visible to existing login sessions")
        clients["t1"].call("/api/admin/user/get-user-list?currentPage=1&limit=10", expected=403)
        passed("teacher authorization does not grant website administrator permissions")
        Client().call("/api/edu/me", expected=401)
        clients["s1"].call("/api/edu/classes", "POST", {"name": "forbidden"}, 403)
        clients["s1"].call("/api/edu/teachers", "POST", {"username": names["s1"]}, 403)
        passed("anonymous and student accounts cannot create classes or grant teachers")
        a, a2, b = create(clients["t1"], "A"), create(clients["t1"], "A2"), create(clients["t2"], "B")
        assert a["ownerUid"] == uids["t1"]
        clients["t2"].call("/api/edu/classes/%s" % a["id"], "PUT", {"name": "forbidden"}, 403)
        clients["t2"].call("/api/edu/classes/%s/members" % a["id"], expected=403)
        clients["t1"].call("/api/edu/classes/%s" % b["id"], expected=403)
        passed("owners derive from login; unrelated teachers cannot read or manage classes")
        enroll(a["id"], ["s1", "s1", "s2"])
        enroll(a2["id"], ["s1"])
        assert root.call("/api/edu/classes/%s" % a["id"])["studentCount"] == 2
        mine = clients["s1"].call("/api/edu/classes")["records"]
        assert {row["id"] for row in mine} == {a["id"], a2["id"]}
        assert not clients["s1"].call("/api/edu/classes/%s" % a["id"])["canManage"]
        clients["s1"].call("/api/edu/classes/%s/members" % a["id"], expected=403)
        clients["s1"].call("/api/edu/classes/%s" % b["id"], expected=403)
        passed("multi-class membership, duplicate protection, student privacy and isolation")
        root.call("/api/edu/classes/%s/members" % a["id"], "POST", {"usernames": [names["s3"], prefix + "_missing"], "role": "STUDENT"}, 400)
        clients["s3"].call("/api/edu/classes/%s" % a["id"], expected=403)
        enroll(a["id"], ["s2"], "TEACHER", 400)
        root.call("/api/edu/classes/%s/members/%s" % (a["id"], uids["t1"]), "DELETE", expected=400)
        passed("invalid batches are atomic; ungranted teachers and owner removal are rejected")
        root.call("/api/edu/classes/%s/members/%s" % (a["id"], uids["s1"]), "DELETE")
        clients["s1"].call("/api/edu/classes/%s" % a["id"], expected=403)
        clients["s1"].call("/api/edu/classes/%s" % a2["id"])
        enroll(a["id"], ["s1"])
        passed("removal revokes only that class; rejoining restores the same membership")
        for state in (1, 0):
            clients["t1"].call("/api/edu/classes/%s" % a["id"], "PUT", {"name": a["name"], "description": "", "status": state})
            clients["s1"].call("/api/edu/classes/%s" % a["id"], expected=403 if state else 200)
            if state:
                assert a["id"] not in {row["id"] for row in clients["s1"].call("/api/edu/classes")["records"]}
                enroll(a["id"], ["s3"], expected=400)
        passed("archival hides classes from students and blocks member changes until restored")
        root.call("/api/admin/user/edit-user", "PUT", {"uid": uids["t1"], "username": names["t1"], "realname": "", "email": "", "password": "", "type": 1002, "status": 0, "setNewPwd": False, "titleName": "", "titleColor": ""})
        assert clients["t1"].call("/api/edu/me")["isTeacher"]
        passed("legacy user editing preserves the additional teacher role")
        root.call("/api/edu/teachers/" + uids["t1"], "DELETE")
        assert not clients["t1"].call("/api/edu/me")["isTeacher"]
        clients["t1"].call("/api/edu/classes", "POST", {"name": "forbidden"}, 403)
        clients["t1"].call("/api/edu/classes/%s" % a["id"], "PUT", {"name": "forbidden"}, 403)
        assert clients["t2"].call("/api/edu/me")["isTeacher"]
        passed("teacher revocation applies to the existing token without affecting others")
        latest = original_ids()
        assert all(values.issubset(latest[key]) for key, values in baseline.items())
        passed("pre-existing accounts, problems and submission IDs are retained")
    finally:
        # Restrict SQL cleanup to IDs returned by THIS run and owners created by
        # THIS run. Never delete by a broad prefix, existing class, or real user.
        if classes:
            owned = {int(value) for value in sql("SELECT id FROM edu_class WHERE id IN (%s) AND owner_uid IN (%s);" %
                     (",".join(map(str, classes)), ",".join("'%s'" % uid for uid in uids.values()))).splitlines()}
            if owned != set(classes):
                raise RuntimeError("Fixture ownership check failed; cleanup stopped")
            ids = ",".join(map(str, classes))
            sql("DELETE FROM edu_class_member WHERE class_id IN (%s); DELETE FROM edu_class WHERE id IN (%s);" % (ids, ids))
        if uids:
            root.call("/api/admin/user/delete-user", "DELETE", {"ids": list(uids.values())})
    report = {"Passed": True, "Checks": checks, "FixtureCleanup": True,
              "OriginalCounts": {key: len(value) for key, value in baseline.items()},
              "MigrationVersions": sql("SELECT version FROM edu_schema_history ORDER BY version;").splitlines()}
    target = Path(__file__).resolve().parents[1] / "logs/classes-acceptance.json"
    target.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("CLASS ACCEPTANCE PASSED: " + str(target))


if __name__ == "__main__":
    os.umask(0o077)
    try:
        main()
    except (RuntimeError, OSError, ValueError, AssertionError) as error:
        print("CLASS ACCEPTANCE FAILED: " + str(error), file=sys.stderr)
        sys.exit(1)
