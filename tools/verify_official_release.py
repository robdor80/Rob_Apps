#!/usr/bin/env python3
"""RobPublisher only: preserve the published Android identity. No signing keys."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fingerprint(value):
    return value.replace(":", "").strip().lower()


def metadata(tools, apk):
    output = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output, re.M)
    require(match is not None, "Missing Android package metadata")
    require("application-debuggable" not in output, "Official APK must not be debuggable")
    require("application-label:'RobPublisher'" in output, "Official application label changed")
    require("sdkVersion:'26'" in output and "targetSdkVersion:'36'" in output, "Unexpected SDK policy")
    return match[1], int(match[2]), match[3]


def signer(tools, apk, expected):
    output = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$", output, re.M)
    require(certificates == [expected], "APK does not use the original official signing certificate")
    require("Verified using v2 scheme (APK Signature Scheme v2): true" in output, "APK v2 signature required")
    subprocess.run([str(tools / "zipalign"), "-c", "-p", "4", str(apk)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--baseline-apk", type=Path, required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--signed", action="store_true")
    parser.add_argument("--check-signing-pins", action="store_true")
    args = parser.parse_args()
    baseline = json.loads((ROOT / ".github/robpublisher-official.json").read_text())
    require(hashlib.sha256(args.baseline_apk.read_bytes()).hexdigest() == baseline["apkSha256"], "Historical APK checksum mismatch")
    require(metadata(args.build_tools, args.baseline_apk) == (baseline["packageName"], baseline["versionCode"], baseline["versionName"]), "Historical APK metadata mismatch")
    signer(args.build_tools, args.baseline_apk, baseline["certificateSha256"])
    if args.check_signing_pins:
        for name in ("CERTIFICATE_SHA256", "RELEASE_CERTIFICATE_SHA256"):
            require(fingerprint(os.environ.get(name, "")) == baseline["certificateSha256"],
                    name + " must match the historical official certificate; restore the original configuration, never replace the key")
    if args.apk:
        package, code, name = metadata(args.build_tools, args.apk)
        require(package == baseline["packageName"], "Official package changed")
        require(code > baseline["versionCode"], "versionCode must exceed the historical official version")
        require(re.fullmatch(r"\d+\.\d+\.\d+", name), "Official versionName must be a stable numeric version")
        if args.signed:
            signer(args.build_tools, args.apk, baseline["certificateSha256"])
        print(json.dumps(dict(packageName=package, versionCode=code, versionName=name,
                              sha256=hashlib.sha256(args.apk.read_bytes()).hexdigest()), indent=2))
    require(args.apk is not None or args.check_signing_pins, "Select APK verification or signing configuration verification")
    print("Official identity and update compatibility: PASS (tablet behaviour still requires device evidence)")


if __name__ == "__main__":
    main()
