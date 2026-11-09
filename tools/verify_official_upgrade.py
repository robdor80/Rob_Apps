#!/usr/bin/env python3
"""Install the two real official APKs on an isolated, rootable CI emulator.

Uses adb install -r, Android's package manager, and synthetic local data only.
Does not authorize GitHub or assert persistence of a real user's OAuth session.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

PACKAGE = "es.robertodorado.robpublisher"
DATA = "/data/user/0/" + PACKAGE


def adb(*args, check=True):
    result = subprocess.run(["adb", *args], text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError("ADB operation failed: " + result.stdout + result.stderr)
    return result


def shell(*args):
    return adb("shell", *args).stdout.strip()


def version():
    match = re.search(r"versionCode=(\d+)", shell("dumpsys", "package", PACKAGE))
    assert match, "Installed package version unavailable"
    return int(match[1])


def launch():
    result = shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    assert "Status: ok" in result, result
    assert shell("pidof", PACKAGE), "Official app did not remain running"
    adb("shell", "am", "force-stop", PACKAGE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    folder = args.directory.resolve()
    published = json.loads((folder / "catalog-verification.json").read_text())
    adb("root")
    adb("wait-for-device")
    assert shell("id", "-u") == "0", "Use the default rootable CI system image"
    assert "Success" in adb("install", str(folder / "baseline/Android-release.apk")).stdout
    assert version() == 3, "Historical version 1.0.2 required"
    launch()
    owner = shell("stat", "-c", "%u:%g", DATA)
    adb("shell", "mkdir", "-p", DATA + "/files", DATA + "/shared_prefs")
    adb("shell", "chown", owner, DATA + "/files", DATA + "/shared_prefs")
    adb("shell", "chmod", "700", DATA + "/files", DATA + "/shared_prefs")
    marker = folder / "upgrade-marker.txt"
    marker.write_text("RobPublisher official upgrade CI sentinel\n")
    preferences = folder / "upgrade-preferences.xml"
    preferences.write_text('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map><set name="repositories"><string>robdor80/Rob_Apps</string></set><string name="upgrade_probe">preserve-official-data</string></map>\n')
    probes = ((marker, DATA + "/files/upgrade-marker.txt"),
              (preferences, DATA + "/shared_prefs/robpublisher.xml"))
    for source, destination in probes:
        adb("push", str(source), destination)
        adb("shell", "chown", owner, destination)
        adb("shell", "chmod", "600", destination)
    assert "Success" in adb("install", "-r", str(folder / "Android-release.apk")).stdout
    assert version() == published["versionCode"], "Update did not install the official published version"
    assert shell("stat", "-c", "%u:%g", DATA) == owner, "Application UID changed"
    launch()
    for source, destination in probes:
        retained = folder / (source.name + ".retained")
        adb("pull", destination, str(retained))
        assert hashlib.sha256(source.read_bytes()).digest() == hashlib.sha256(retained.read_bytes()).digest(), "Update lost local data/preferences"
    downgrade = adb("install", "-r", str(folder / "baseline/Android-release.apk"), check=False)
    assert downgrade.returncode != 0 and "INSTALL_FAILED_VERSION_DOWNGRADE" in downgrade.stdout + downgrade.stderr, "Android must reject a downgrade"
    assert version() == published["versionCode"]
    report = dict(result="PASS", packageName=PACKAGE, fromVersionCode=3, toVersionCode=published["versionCode"],
                  sameUid=True, localFilePreserved=True, sharedPreferencesPreserved=True, launchAfterUpgrade=True,
                  downgradeRejected=True, realOAuthSessionTested=False,
                  limit="Isolated Android emulator and adb install -r; no physical tablet or RobStore UI interaction")
    (folder / "android-upgrade-verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
