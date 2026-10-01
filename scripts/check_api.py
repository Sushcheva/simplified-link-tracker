#!/usr/bin/env python3
"""Smoke test against a running app. Uses isolated URLs and deletes created test links."""
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

origin = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
fixture = os.environ.get("FIXTURE_URL")
created = []
checks = 0


def request(method, path, data=None, expected=200):
    global checks
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(origin + path, data=body, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    raw = response.read().decode()
    assert response.status == expected, f"{method} {path}: expected {expected}, got {response.status}: {raw}"
    checks += 1
    if response.headers.get("Content-Type", "").startswith("application/json") or "+json" in response.headers.get("Content-Type", ""):
        return json.loads(raw)
    return raw


def set_fixture(version):
    req = urllib.request.Request(fixture + "/control", data=json.dumps({"version": version}).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    urllib.request.urlopen(req, timeout=5).close()


try:
    assert "Ваши ссылки" in request("GET", "/")
    assert "renderLinks" in request("GET", "/app.js")
    assert ".sidebar" in request("GET", "/styles.css")
    assert request("GET", "/actuator/health")["status"] == "UP"
    request("GET", "/api/links?page=-1", expected=400)
    request("GET", "/api/links?size=101", expected=400)
    payload = {"url": "https://github.com/smoke-test/" + uuid.uuid4().hex,
               "title": "Тестовая ссылка", "tags": ["java", "java"], "enabled": False}
    link = request("POST", "/api/links", payload, 201)
    created.append(link["id"])
    assert link["tags"] == ["java"]
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
    print(f"PASS: {checks} HTTP checks; CRUD, validation, frontend" + (", monitoring and update history" if fixture else ""))
finally:
    for link_id in created:
        try:
            request("DELETE", f"/api/links/{link_id}", expected=204)
        except Exception as error:
            print(f"Cleanup failed for test link {link_id}: {error}", file=sys.stderr)
