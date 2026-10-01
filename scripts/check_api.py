#!/usr/bin/env python3
"""Smoke test against a running app. Uses isolated URLs and deletes created test links."""
import json
import http.cookiejar
import urllib.parse
import os
import sys
import urllib.error
import urllib.request
import uuid

origin = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
fixture = os.environ.get("FIXTURE_URL")
created = []
checks = 0
second_origin = os.environ.get("SECOND_ORIGIN")
password = "verification-password-2026"


class Client:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = None

    def refresh_csrf(self, base=None):
        self.csrf = self.request("GET", "/api/auth/csrf", base=base)

    def request(self, method, path, data=None, expected=200, csrf=True, base=None):
        global checks
        unsafe = method not in {"GET", "HEAD", "OPTIONS"}
        if unsafe and csrf and not self.csrf:
            self.refresh_csrf(base)
        form = path == "/api/auth/login"
        body = None if data is None else (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
        headers = {"Content-Type": "application/x-www-form-urlencoded" if form else "application/json"}
        if unsafe and csrf:
            headers[self.csrf["headerName"]] = self.csrf["token"]
        req = urllib.request.Request((base or origin) + path, data=body, method=method, headers=headers)
        try:
            response = self.opener.open(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            assert response.status == expected, f"{method} {path}: expected {expected}, got {response.status}: {raw}"
            checks += 1
            content_type = response.headers.get("Content-Type", "")
            return json.loads(raw) if "application/json" in content_type or "+json" in content_type else raw

    def register_and_login(self):
        email = "check-" + uuid.uuid4().hex + "@example.test"
        account = self.request("POST", "/api/auth/register", {"email": email, "password": password}, 201)
        result = self.request("POST", "/api/auth/login", {"email": email, "password": password})
        assert result == account and set(account) == {"id", "email"}
        self.refresh_csrf()
        return account


client = Client()
request = client.request
other = Client()
other_created = []

def set_fixture(version):
    req = urllib.request.Request(fixture + "/control", data=json.dumps({"version": version}).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    urllib.request.urlopen(req, timeout=5).close()


try:
    assert "Ваши ссылки" in request("GET", "/")
    assert "renderLinks" in request("GET", "/app.js")
    assert ".sidebar" in request("GET", "/styles.css")
    assert request("GET", "/actuator/health")["status"] == "UP"
    request("GET", "/api/links", expected=401)
    request("GET", "/api/updates", expected=401)
    account = client.register_and_login()
    assert request("GET", "/api/auth/me") == account
    request("POST", "/api/links", {}, expected=403, csrf=False)
    other.register_and_login()
    request("GET", "/api/links?page=-1", expected=400)
    request("GET", "/api/links?size=101", expected=400)
    payload = {"url": "https://github.com/smoke-test/" + uuid.uuid4().hex,
               "title": "Тестовая ссылка", "tags": ["java", "java"], "enabled": False}
    link = request("POST", "/api/links", payload, 201)
    created.append(link["id"])
    assert link["tags"] == ["java"]
    assert other.request("GET", "/api/links")["total"] == 0
    for method, path, body in [
        ("GET", f"/api/links/{link['id']}", None),
        ("PUT", f"/api/links/{link['id']}", payload),
        ("DELETE", f"/api/links/{link['id']}", None),
        ("POST", f"/api/links/{link['id']}/check", None),
        ("GET", f"/api/updates?linkId={link['id']}", None),
    ]:
        other.request(method, path, body, 404)
    same = other.request("POST", "/api/links", payload, 201)
    other_created.append(same["id"])
    assert same["id"] != link["id"]
    if second_origin:
        assert request("GET", "/api/auth/me", base=second_origin) == account
        assert request("GET", f"/api/links/{link['id']}", base=second_origin)["id"] == link["id"]
        request("PUT", f"/api/links/{link['id']}", payload, base=second_origin)
    assert request("GET", f"/api/links/{link['id']}")["url"] == payload["url"]
    request("POST", "/api/links", payload, 409)
    request("POST", "/api/links", dict(payload, url="https://github.com.evil.test/a/b"), 400)
    request("POST", "/api/links", dict(payload, title="   "), 400)
    request("POST", "/api/links", dict(payload, tags=["x" * 33]), 400)
    updated = request("PUT", f"/api/links/{link['id']}", dict(payload, title="Изменено", tags=["backend"]))
    assert updated["title"] == "Изменено" and updated["tags"] == ["backend"]
    assert any(item["id"] == link["id"] for item in request("GET", "/api/links?tag=backend")["links"])
    assert not any(item["id"] == link["id"] for item in request("GET", "/api/links?tag=missing-tag")["links"])

    if fixture:
        set_fixture(1)
        first = request("POST", f"/api/links/{link['id']}/check")
        assert first["lastSeenAt"] and not first["lastError"], first
        assert request("GET", f"/api/updates?linkId={link['id']}") == []
        set_fixture(2)
        request("POST", f"/api/links/{link['id']}/check")
        assert len(request("GET", f"/api/updates?linkId={link['id']}")) == 1
        request("POST", f"/api/links/{link['id']}/check")
        assert len(request("GET", f"/api/updates?linkId={link['id']}")) == 1
        assert other.request("GET", "/api/updates") == []
        set_fixture(3)  # temporary API failure must keep the last successful checkpoint
        failed = request("POST", f"/api/links/{link['id']}/check")
        assert failed["lastError"] and "2026-01-02" in failed["lastSeenAt"], failed
        set_fixture(1)
        changed = request("PUT", f"/api/links/{link['id']}", dict(payload, url=payload["url"] + "-new"))
        assert changed["lastSeenAt"] is None
        assert request("GET", f"/api/updates?linkId={link['id']}") == []
        so = request("POST", "/api/links", dict(payload, url="https://stackoverflow.com/questions/123456/test-question"), 201)
        created.append(so["id"])
        checked = request("POST", f"/api/links/{so['id']}/check")
        assert checked["lastSeenAt"] and not checked["lastError"], checked

    request("DELETE", f"/api/links/{link['id']}", expected=204)
    created.remove(link["id"])
    request("GET", f"/api/links/{link['id']}", expected=404)
    request("DELETE", f"/api/links/{link['id']}", expected=404)
    for link_id in list(created):
        request("DELETE", f"/api/links/{link_id}", expected=204)
        created.remove(link_id)
    request("POST", "/api/auth/logout", expected=204)
    request("GET", "/api/auth/me", expected=401)
    if second_origin:
        request("GET", "/api/links", expected=401, base=second_origin)
    print(f"PASS: {checks} HTTP checks; authentication, CSRF, private CRUD, frontend"
          + (", monitoring and update history" if fixture else "")
          + (", shared sessions on two servers" if second_origin else ""))
finally:
    for link_id in other_created:
        other.request("DELETE", f"/api/links/{link_id}", expected=204)
    for link_id in created:
        try:
            request("DELETE", f"/api/links/{link_id}", expected=204)
        except Exception as error:
            print(f"Cleanup failed for test link {link_id}: {error}", file=sys.stderr)
