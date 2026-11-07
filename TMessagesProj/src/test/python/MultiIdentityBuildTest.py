#!/usr/bin/env python3
"""Real-Gradle regression for install identities (NNN_APP_ID, comma-separated).

Drives the project's own Gradle entry points instead of grepping build scripts:
it runs the per-variant identity resource tasks and `assembleRelease --dry-run`,
so a change that stops one `assembleRelease` from covering every identity, or
that mixes up per-identity labels/application ids, fails here.

    python TMessagesProj/src/test/python/MultiIdentityBuildTest.py

Each Gradle invocation is logged to TMessagesProj/build/multi-identity-test/.
"""

import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
LOG_DIR = ROOT / "TMessagesProj" / "build" / "multi-identity-test"
IDENTITY_RES = ROOT / "TMessagesProj" / "build" / "generated" / "identityRes"

GRADLEW = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
ANDROID = "{http://schemas.android.com/apk/res/android}"


def gradle(name, args):
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log = LOG_DIR / f"{name}.log"
    # --no-scan: this workspace auto-publishes build scans; never upload them from tests.
    cmd = [str(GRADLEW), "--console=plain", "--no-scan"] + args
    if sys.platform == "win32":
        cmd = ["cmd", "/c"] + cmd
    result = subprocess.run(
        cmd,
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=3600,
    )
    log.write_text("$ " + " ".join(args) + "\n" + result.stdout, encoding="utf-8")
    return result, log


def read(path):
    return path.read_text(encoding="utf-8")


def xml_root(path):
    return ET.fromstring(read(path))


def identity(label, file_name):
    return IDENTITY_RES / label / "res" / file_name


def check(condition, message, log=None):
    if not condition:
        hint = f" (log: {log})" if log else ""
        raise AssertionError(message + hint)


def multi_identity_builds_one_variant_per_id():
    ids = "xyz.test.nnngrama,xyz.test.nnngramb"
    result, log = gradle(
        "multi-identity-res",
        [
            ":TMessagesProj:generateApp1ReleaseIdentityRes",
            ":TMessagesProj:generateApp2ReleaseIdentityRes",
            "-PNNN_APP_ID=" + ids,
        ],
    )
    check(result.returncode == 0, "identity tasks failed:\n" + result.stdout[-2000:], log)

    for variant, app_id, label in (
        ("app1Release", "xyz.test.nnngrama", "NnngramX-a"),
        ("app2Release", "xyz.test.nnngramb", "NnngramX-b"),
    ):
        strings = identity(variant, "values/strings_identity.xml")
        check(strings.is_file(), f"{variant}: missing generated label resource", log)
        label_node = xml_root(strings).find("string[@name='NnnAppLabel']")
        check(label_node is not None, f"{variant}: no NnnAppLabel in {strings}", log)
        check(
            label_node.text == label,
            f"{variant}: launcher label is {label_node.text!r}, expected {label!r}",
            log,
        )
        auth = identity(variant, "xml/auth.xml")
        check(auth.is_file(), f"{variant}: missing generated authenticator xml", log)
        account_types = [element.get(ANDROID + "accountType") for element in xml_root(auth).iter()]
        check(
            app_id in account_types,
            f"{variant}: authenticator accountType {account_types}, expected {app_id}",
            log,
        )


def one_assemble_release_covers_every_identity():
    result, log = gradle(
        "multi-assembly",
        [
            ":TMessagesProj:assembleRelease",
            "--dry-run",
            "-PNNN_APP_ID=xyz.test.nnngrama,xyz.test.nnngramb",
        ],
    )
    check(result.returncode == 0, "assembleRelease dry run failed:\n" + result.stdout[-2000:], log)
    for task in (":TMessagesProj:assembleApp1Release", ":TMessagesProj:assembleApp2Release"):
        check(task in result.stdout, f"{task} missing from the assembleRelease task graph", log)


def single_identity_keeps_original_tasks_and_paths():
    app_id = "xyz.test.nnngramz"
    result, log = gradle(
        "single-identity-res",
        [":TMessagesProj:generateReleaseIdentityRes", "-PNNN_APP_ID=" + app_id],
    )
    check(result.returncode == 0, "identity task failed:\n" + result.stdout[-2000:], log)
    strings = identity("release", "values/strings_identity.xml")
    check(strings.is_file(), "single identity must keep generated/identityRes/release", log)
    label_node = xml_root(strings).find("string[@name='NnnAppLabel']")
    check(label_node is not None, f"no NnnAppLabel in {strings}", log)
    check(label_node.text == "NnngramX-z", f"launcher label is {label_node.text!r}", log)
    account_types = [
        element.get(ANDROID + "accountType")
        for element in xml_root(identity("release", "xml/auth.xml")).iter()
    ]
    check(app_id in account_types, f"authenticator accountType {account_types}", log)

    result, log = gradle(
        "single-assembly",
        [":TMessagesProj:assembleRelease", "--dry-run", "-PNNN_APP_ID=" + app_id],
    )
    check(result.returncode == 0, "assembleRelease dry run failed:\n" + result.stdout[-2000:], log)
    check(":TMessagesProj:assembleRelease" in result.stdout, "assembleRelease missing from its own task graph", log)
    for task in ("assembleApp1Release", "generateApp1ReleaseIdentityRes"):
        check(task not in result.stdout, f"{task}: single identity must not gain flavor tasks", log)


def rejects_invalid_identity_lists():
    for name, app_ids, expected in (
        ("empty-item", "xyz.test.nnngrama,,xyz.test.nnngramb", "empty install identity"),
        ("invalid-id", "xyz.test.nnngrama,not a package", "invalid install identity"),
        ("duplicate-id", "xyz.test.nnngrama,xyz.test.nnngrama", "duplicate install identity"),
    ):
        result, log = gradle(name, [":TMessagesProj:tasks", "-PNNN_APP_ID=" + app_ids])
        check(result.returncode != 0, f"{name}: build accepted NNN_APP_ID={app_ids!r}", log)
        check(
            expected in result.stdout,
            f"{name}: failure does not name the problem ({expected}):\n{result.stdout[-1500:]}",
            log,
        )


def main():
    scenarios = {
        scenario.__name__: scenario
        for scenario in (
            multi_identity_builds_one_variant_per_id,
            one_assemble_release_covers_every_identity,
            single_identity_keeps_original_tasks_and_paths,
            rejects_invalid_identity_lists,
        )
    }
    selected = [scenarios[name] for name in sys.argv[1:]] or list(scenarios.values())
    failed = []
    for scenario in selected:
        try:
            scenario()
            print(f"PASS {scenario.__name__}")
        except AssertionError as error:
            failed.append(scenario.__name__)
            print(f"FAIL {scenario.__name__}: {error}")
    if failed:
        sys.exit(1)
    print(f"OK: {len(selected)} scenario(s) passed")


if __name__ == "__main__":
    main()
