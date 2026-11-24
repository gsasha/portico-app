#!/usr/bin/env python3
"""Tiny Gitea release client for scripts/release.sh (stdlib only; the token is never printed).

Config, all outside the repo (no default points at any particular server):
  PORTICO_GITEA_URL     base URL of the Gitea server (required)
  PORTICO_GITEA_TOKEN   API token (required)
  PORTICO_GITEA_ENV     optional env file (KEY=value lines) that may hold those two instead
  PORTICO_GITEA_REPO    owner/name (required)

Commands (exit 0 = yes/done, 1 = no, 2 = error):
  exists TAG                      0 and prints what exists if a release or a tag TAG exists; 1 if neither
  tags                            print the tag name of every release, one per line
  create TAG SHA NAME BODYFILE    create a published prerelease at commit SHA; print its id
  upload ID FILE ASSETNAME        attach FILE to release ID
  download TAG ASSETNAME OUTFILE  fetch the release asset back into OUTFILE
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

REPO = os.environ.get("PORTICO_GITEA_REPO")
if not REPO:
    sys.exit("gitea.py: set PORTICO_GITEA_REPO (owner/name)")


def _config():
    base, tok = os.environ.get("PORTICO_GITEA_URL"), os.environ.get("PORTICO_GITEA_TOKEN")
    if not (base and tok):
        env = {}
        path = os.environ.get("PORTICO_GITEA_ENV")
        try:
            with open(path) as f:  # PORTICO_GITEA_ENV unset -> open(None) raises TypeError, caught below
                for line in f:
                    if "=" in line and not line.lstrip().startswith("#"):
                        k, v = line.rstrip("\n").split("=", 1)
                        env[k.strip()] = v.strip().strip("\"'")
        except (OSError, TypeError):
            pass
        base = base or env.get("PORTICO_GITEA_URL")
        tok = tok or env.get("PORTICO_GITEA_TOKEN")
    if not (base and tok):
        sys.exit("gitea.py: no Gitea URL/token (set PORTICO_GITEA_URL and PORTICO_GITEA_TOKEN, or PORTICO_GITEA_ENV)")
    return base.rstrip("/"), tok


BASE, TOK = _config()


def _req(method, url, data=None, headers=None, timeout=600):
    h = {"Authorization": "token " + TOK}
    h.update(headers or {})
    r = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as f:
            return f.status, f.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def api(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    s, raw = _req(method, BASE + "/api/v1/repos/" + REPO + path, data, {"Content-Type": "application/json"}, 60)
    return s, (json.loads(raw) if raw else {})


def die(msg):
    print("gitea.py: " + msg, file=sys.stderr)
    sys.exit(2)


def main(a):
    cmd = a[0] if a else ""
    if cmd == "exists" and len(a) == 2:
        tag, found = urllib.parse.quote(a[1], safe=""), []
        for what, path in (("release", "/releases/tags/" + tag), ("tag", "/tags/" + tag)):
            s, _ = api("GET", path)
            if s == 200:
                found.append(what)
            elif s != 404:
                die("HTTP %d asking whether %s %s exists (not 200/404; check the token, a bad one returns 404 too)" % (s, what, a[1]))
        # A bad token also answers 404; make sure the token works at all before trusting a 'no'.
        if not found:
            s, _ = api("GET", "")
            if s != 200:
                die("HTTP %d reading the repository itself; credentials or URL are wrong, so 'does not exist' cannot be trusted" % s)
        print(" and ".join(found))
        return 0 if found else 1
    if cmd == "tags" and len(a) == 1:
        page = 1
        while True:
            s, d = api("GET", "/releases?limit=50&page=%d" % page)
            if s != 200:
                die("HTTP %d listing releases" % s)
            for r in d:
                print(r["tag_name"])
            if len(d) < 50:
                return 0
            page += 1
    if cmd == "create" and len(a) == 5:
        tag, sha, name, bodyfile = a[1:]
        with open(bodyfile) as f:
            text = f.read()
        s, d = api("POST", "/releases", {"tag_name": tag, "target_commitish": sha, "name": name,
                                         "body": text, "prerelease": True, "draft": False})
        if s != 201:
            die("HTTP %d creating release %s: %s" % (s, tag, str(d)[:300]))
        print(d["id"])
        return 0
    if cmd == "upload" and len(a) == 4:
        rid, path, name = a[1:]
        with open(path, "rb") as f:
            data = f.read()
        b = uuid.uuid4().hex
        pre = ('--%s\r\nContent-Disposition: form-data; name="attachment"; filename="%s"\r\n'
               'Content-Type: application/vnd.android.package-archive\r\n\r\n' % (b, name)).encode()
        s, raw = _req("POST", "%s/api/v1/repos/%s/releases/%s/assets?name=%s" % (BASE, REPO, rid, urllib.parse.quote(name)),
                      pre + data + ("\r\n--%s--\r\n" % b).encode(),
                      {"Content-Type": "multipart/form-data; boundary=" + b})
        if s != 201:
            die("HTTP %d uploading %s: %s" % (s, name, raw[:300].decode("utf-8", "replace")))
        d = json.loads(raw)
        print("%s %d" % (d["name"], d["size"]))
        return 0
    if cmd == "download" and len(a) == 4:
        tag, name, out = a[1:]
        url = "%s/%s/releases/download/%s/%s" % (BASE, REPO, urllib.parse.quote(tag, safe=""), urllib.parse.quote(name))
        s, raw = _req("GET", url)
        if s != 200:
            die("HTTP %d downloading %s back" % (s, name))
        with open(out, "wb") as f:
            f.write(raw)
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
