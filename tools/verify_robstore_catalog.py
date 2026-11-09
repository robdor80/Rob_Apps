#!/usr/bin/env python3
"""Validate the live RobPublisher catalog contract and retain public test inputs."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
REPO = "robdor80/Rob_Apps"


def require(value, message):
    if not value:
        raise ValueError(message)


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))


def contents(path, tag):
    item = api(f"repos/{REPO}/contents/{path}?ref={quote(tag, safe='')}")
    require(item["encoding"] == "base64", "Unsupported GitHub content encoding")
    return base64.b64decode(item["content"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    directory = args.directory.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    baseline = json.loads((ROOT / ".github/robpublisher-official.json").read_text())
    repo = api(f"repos/{REPO}")
    require(repo["owner"]["login"] == "robdor80" and repo["full_name"] == REPO, "Wrong repository owner")
    require(not repo["private"] and not repo["archived"] and repo["visibility"] == "public", "Catalog repository must be public and active")
    require("robstore-app" in repo["topics"], "Missing robstore-app catalog topic")
    releases = []
    for page in range(1, 11):
        part = api(f"repos/{REPO}/releases?per_page=100&page={page}")
        releases.extend(part)
        if len(part) < 100:
            break
    else:
        raise ValueError("Incomplete Release pagination")
    stable = [r for r in releases if not r["draft"] and not r["prerelease"] and r.get("published_at")]
    require(stable, "No stable official Release")
    release = max(stable, key=lambda r: (r["published_at"], r["id"]))
    tag = release["tag_name"]
    manifest_bytes = contents("robstore.json", tag)
    manifest = json.loads(manifest_bytes)
    contract = json.loads(contents("robstore-update.json", tag))
    receipt = json.loads(contents("robstore-distribution.json", tag))
    settings = json.loads(contents(".github/android-distribution.json", tag))
    require(settings["robstorePolicy"] is False, "RobStore's own package policy cannot be used for RobPublisher")
    require(manifest["schemaVersion"] == 1 and manifest["name"] == "RobPublisher", "Wrong catalog manifest")
    require(manifest["packageName"] == contract["packageName"] == baseline["packageName"], "Official package mismatch")
    require(type(manifest["versionCode"]) is int and manifest["versionCode"] > baseline["versionCode"], "Official update must increase versionCode")
    require(manifest["versionCode"] == contract["versionCode"] and manifest["versionName"] == contract["versionName"], "Catalog version mismatch")
    require(re.fullmatch(r"\d+\.\d+\.\d+", manifest["versionName"]), "Experimental version forbidden")
    require(manifest["apkAssetName"] == contract["apkAssetName"] == "Android-release.apk", "Wrong official APK asset")
    require(receipt["repository"] == REPO and receipt["channel"] == contract["channel"] == "release" and receipt["tag"] == tag, "Wrong RS-05 receipt")
    require(receipt["certificateSha256"] == baseline["certificateSha256"], "Official certificate changed")
    icon = contents(manifest["icon"], tag)
    require(icon.startswith(b"\x89PNG\r\n\x1a\n") and len(icon) <= 512 * 1024, "Catalog icon invalid")
    assets = release["assets"]
    matching = [a for a in assets if a["name"] == manifest["apkAssetName"]]
    require(len(matching) == 1, "Official APK absent or ambiguous")
    asset = matching[0]
    expected_url = f"https://github.com/{REPO}/releases/download/{tag}/Android-release.apk"
    require(asset["browser_download_url"] == expected_url and asset["state"] == "uploaded", "APK URL/state mismatch")
    subprocess.run(["gh", "release", "download", tag, "--repo", REPO, "--pattern", "Android-release.apk", "--pattern", "SHA256SUMS", "--dir", str(directory)], check=True)
    old = directory / "baseline"
    subprocess.run(["gh", "release", "download", baseline["tag"], "--repo", REPO, "--pattern", baseline["asset"], "--dir", str(old)], check=True)
    apk = directory / manifest["apkAssetName"]
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    require(asset["size"] == apk.stat().st_size and asset["digest"] == "sha256:" + digest, "GitHub asset integrity mismatch")
    require(digest == receipt["apkSha256"] == contract["sha256"], "RS-05 digest mismatch")
    require((directory / "SHA256SUMS").read_text() == f"{digest}  Android-release.apk\n", "Checksum manifest mismatch")
    subprocess.run(["python3", str(ROOT / "tools/verify_official_release.py"), "--build-tools", str(args.build_tools), "--baseline-apk", str(old / baseline["asset"]), "--apk", str(apk), "--signed"], check=True)
    for name, data in (("repository.json", repo), ("releases.json", releases), ("assets.json", assets), ("contract.json", contract)):
        (directory / name).write_text(json.dumps(data, ensure_ascii=False))
    (directory / "manifest.json").write_bytes(manifest_bytes)
    summary = dict(repository=REPO, topic="robstore-app", tag=tag, versionName=manifest["versionName"], versionCode=manifest["versionCode"], packageName=manifest["packageName"], sha256=digest, certificateSha256=baseline["certificateSha256"], url=expected_url)
    (directory / "catalog-verification.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
