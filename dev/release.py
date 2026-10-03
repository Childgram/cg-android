#!/usr/bin/env python3
"""Package a verified APK, or build the public feed from published GitHub releases."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "Childgram/cg-android"
RELEASES = f"https://github.com/{REPOSITORY}/releases"
VERSION = r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?"
MAX_APK = 250 * 1024 * 1024


def version():
    props = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                 if "=" in line and not line.startswith("#"))
    name, code = props["CHILDGRAM_VERSION_NAME"], int(props["CHILDGRAM_VERSION_CODE"])
    if not re.fullmatch(VERSION, name) or not 0 < code <= 2147483647:
        raise ValueError("Invalid Childgram version")
    return name, code


def validate(data):
    assert data["schema_version"] == 1 and data["available"] is True, "Invalid feed schema"
    assert data["package"] == "org.childgram", "Unexpected package"
    name = data["version"]
    assert re.fullmatch(VERSION, name), "Invalid version"
    assert type(data["version_code"]) is int and 0 < data["version_code"] <= 2147483647, "Invalid version code"
    assert type(data["size"]) is int and 0 < data["size"] <= MAX_APK, "Invalid APK size"
    assert re.fullmatch(r"[0-9a-f]{64}", data["sha256"]), "Invalid hash"
    assert data["file_url"] == f"{RELEASES}/download/v{name}/childgram-{name}-arm64.apk", "Unexpected APK URL"
    assert data["certificate_sha256"] == (ROOT / "dev/release-certificate.sha256").read_text().strip(), "Unexpected certificate"
    assert re.fullmatch(r"[0-9a-f]{40}", data["commit"]), "Invalid source commit"
    assert isinstance(data["changelog"], str), "Invalid changelog"
    assert len(encode(data)) <= 65536, "Manifest exceeds the app's size limit"
    return data


def encode(data):
    return (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode()


def digest(path):
    with path.open("rb") as stream:
        result = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def request_json(url, *, api=False):
    headers = {"User-Agent": "Childgram-release", "Accept": "application/json"}
    # The token is sent only to GitHub's API, never to asset redirects.
    if api and os.environ.get("GH_TOKEN"):
        assert url.startswith("https://api.github.com/"), "Unexpected API host"
        headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        body = response.read((4 * 1024 * 1024 if api else 65536) + 1)
    assert len(body) <= (4 * 1024 * 1024 if api else 65536), "JSON response too large"
    return json.loads(body)


def releases():
    result = []
    page = 1
    while True:
        batch = request_json(f"https://api.github.com/repos/{REPOSITORY}/releases?per_page=100&page={page}", api=True)
        result.extend(batch)
        if len(batch) < 100:
            return result
        page += 1


def select_release(items, fetch=request_json):
    candidates = []
    codes = set()
    for release in items:
        tag = release["tag_name"]
        if release["draft"] or not re.fullmatch("v" + VERSION, tag):
            continue
        assets = {asset["name"]: asset for asset in release["assets"]}
        # Published Childgram tags must be complete. A broken publication must
        # fail deployment and preserve the previous working Pages feed.
        assert "android.json" in assets, f"Missing manifest in {tag}"
        manifest_url = f"{RELEASES}/download/{tag}/android.json"
        assert assets["android.json"]["browser_download_url"] == manifest_url, "Unexpected manifest URL"
        data = validate(fetch(manifest_url))
        assert not data.get("local_fixture"), "Local test artifacts must never be published"
        assert tag == "v" + data["version"], "Release tag differs from manifest"
        apk = assets[f"childgram-{data['version']}-arm64.apk"]
        assert apk["browser_download_url"] == data["file_url"] and apk["size"] == data["size"], "APK asset differs from manifest"
        assert data["version_code"] not in codes, "Published version codes must be unique"
        codes.add(data["version_code"])
        candidates.append((data, apk))
    return max(candidates, key=lambda pair: pair[0]["version_code"]) if candidates else (None, None)


def verify_asset(data, asset):
    # GitHub records SHA-256 for new uploads. Stream older assets if no digest is available.
    if asset.get("digest"):
        assert asset["digest"] == "sha256:" + data["sha256"], "GitHub APK digest differs from manifest"
        return
    result, total = hashlib.sha256(), 0
    with urllib.request.urlopen(data["file_url"], timeout=60) as response:
        for chunk in iter(lambda: response.read(1024 * 1024), b""):
            total += len(chunk)
            assert total <= data["size"], "APK is larger than declared"
            result.update(chunk)
    assert total == data["size"] and result.hexdigest() == data["sha256"], "APK digest mismatch"


def package(apk, notes, output, local=False):
    name, code = version()
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if not local:
        assert not subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=ROOT), "Commit release sources first, or use --local for a non-publishable fixture"
    data = validate(dict(schema_version=1, available=True, package="org.childgram", version=name,
                         version_code=code, file_url=f"{RELEASES}/download/v{name}/childgram-{name}-arm64.apk",
                         size=apk.stat().st_size, sha256=digest(apk), changelog=notes.read_text().strip(),
                         certificate_sha256=(ROOT / "dev/release-certificate.sha256").read_text().strip(), commit=commit,
                         local_fixture=local))
    output.mkdir(parents=True, exist_ok=False)
    filename = f"childgram-{name}-arm64.apk"
    shutil.copyfile(apk, output / filename)
    (output / "android.json").write_bytes(encode(data))
    (output / "SHA256SUMS").write_text(f"{data['sha256']}  {filename}\n")
    shutil.copyfile(ROOT / "dev/release-certificate.sha256", output / "release-certificate.sha256")
    (output / "notes.md").write_text(("LOCAL FIXTURE — do not publish. Contains uncommitted changes.\n\n" if local else "")
                                   + notes.read_text().strip() + f"\n\nSource: {RELEASES.rsplit('/releases', 1)[0]}/tree/{commit}\n"
                                   + f"\nAndroid ARM64 · version code {code}.\n\nSHA-256: `{data['sha256']}`\n")
    print(f"Prepared v{name} (code {code}) in {output}")


def feed(output):
    data, asset = select_release(releases())
    if data:
        verify_asset(data, asset)
    output.mkdir(parents=True, exist_ok=True)
    (output / "android.json").write_bytes(encode(data or dict(schema_version=1, available=False)))
    (output / "CNAME").write_text("update.childgram.org\n")
    (output / ".nojekyll").touch()
    (output / "index.html").write_text('<!doctype html><html lang="ru"><meta charset="utf-8">'
                                     '<title>Обновления Childgram</title><h1>Обновления Childgram</h1>'
                                     '<p><a href="https://childgram.org">Сайт Childgram</a></p>'
                                     '<p><a href="android.json">Данные Android-релиза</a></p></html>')
    print("Feed prepared: " + (data["version"] if data else "no published release"))


def preflight():
    name, code = version()
    assert subprocess.run(["git", "show-ref", "--verify", "--quiet", "refs/tags/v" + name], cwd=ROOT).returncode == 1, "This tag already exists; choose a new version"
    items = releases()
    assert all(item["tag_name"] != "v" + name for item in items), "This release already exists (possibly as a draft)"
    latest, _ = select_release(items)
    assert latest is None or code > latest["version_code"], "Increase CHILDGRAM_VERSION_CODE before releasing"
    print(f"Release version accepted: v{name} (code {code})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    packaging = commands.add_parser("package")
    packaging.add_argument("--apk", type=Path, default=ROOT / "TMessagesProj_App/build/outputs/apk/afat/release/app.apk")
    packaging.add_argument("--notes", type=Path, default=ROOT / "dev/release-notes.md")
    packaging.add_argument("--output", type=Path, required=True)
    packaging.add_argument("--local", action="store_true", help="Mark uncommitted local tests as non-publishable")
    publication = commands.add_parser("feed")
    publication.add_argument("--output", type=Path, required=True)
    commands.add_parser("preflight")
    args = parser.parse_args()
    if args.command == "package":
        package(args.apk, args.notes, args.output, args.local)
    elif args.command == "feed":
        feed(args.output)
    else:
        preflight()
