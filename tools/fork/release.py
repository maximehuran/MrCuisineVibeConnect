#!/usr/bin/env python3
"""Stable upstream releases -> tested fork APKs. No force pushes or device access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

UPSTREAM = "Wuki60/MrCuisineVibeConnect"
FORK = "maximehuran/MrCuisineVibeConnect"
EMAIL = "maxime.huran@gmail.com"
CERT_SHA256 = "41e69a019f92e2a78ec9c44e68a13ad8adc1293406b85a5616528e317822224c"
OFFICIAL_TAG = re.compile(r"v(\d+)\.(\d+)\.(\d+)")
FORK_TAG = re.compile(r"v(\d+)\.(\d+)\.(\d+)\.(\d+)")


def run(*args, ok=True):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if ok and result.returncode:
        raise RuntimeError(f"{args[0]} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result


def git(*args):
    return run("git", *args).stdout.strip()


def api(path):
    return json.loads(run("gh", "api", path).stdout)


def validate_official(release):
    tag = release.get("tag_name", "")
    if (release.get("draft") or release.get("prerelease") or not release.get("published_at")
            or not OFFICIAL_TAG.fullmatch(tag)):
        raise ValueError("Only published, stable, numeric official releases are supported")
    if not any(a.get("name", "").endswith(".apk") for a in release.get("assets", [])):
        raise ValueError("Official release has no APK yet; retry after publication completes")
    return tag


def next_version(base, tags, previous=None):
    if not re.fullmatch(r"\d+\.\d+\.\d+", base):
        raise ValueError("Unsupported upstream version; review before publishing")
    sequence = max((int(m[4]) for tag in tags if (m := FORK_TAG.fullmatch(tag))), default=0) + 1
    version, code = f"{base}.{sequence}", 10_000 + sequence
    if previous and (code <= previous["version_code"] or
                     tuple(map(int, version.split("."))) <= tuple(map(int, previous["version_name"].split(".")))):
        raise ValueError("Fork version would go backwards; review the upstream release")
    return version, code


def merge_release(sha):
    """Merge in the temporary runner checkout; abort on conflict and never touch origin."""
    result = run("git", "merge", "--no-ff", "--no-edit", sha, ok=False)
    if result.returncode:
        run("git", "merge", "--abort", ok=False)
        raise RuntimeError("Upstream release conflicts with the fork. Resolve and test manually.\n" + result.stdout)


def last_manifest():
    result = run("gh", "api", f"repos/{FORK}/releases/latest", ok=False)
    if result.returncode:
        # Verify the repository is accessible before interpreting a missing latest release.
        api(f"repos/{FORK}")
        if "HTTP 404" in result.stderr:
            return None
        raise RuntimeError(result.stderr)
    release = json.loads(result.stdout)
    if not FORK_TAG.fullmatch(release["tag_name"]):
        raise ValueError("Latest fork release is not a managed fork build")
    raw = run("gh", "release", "download", release["tag_name"], "--repo", FORK,
              "--pattern", "fork-manifest.json", "--output", "-").stdout
    manifest = json.loads(raw)
    if manifest.get("repository") != FORK or manifest.get("tag") != release["tag_name"]:
        raise ValueError("Latest fork release has an inconsistent manifest")
    return manifest


def assert_fork_checkout():
    if os.environ.get("GITHUB_REPOSITORY") != FORK or os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Publishing is restricted to the fork's main branch")
    if git("status", "--porcelain", "--untracked-files=no"):
        raise ValueError("Checkout contains uncommitted changes")


def prepare(plan_path):
    assert_fork_checkout()
    git("config", "user.name", "Maxime Huran")
    git("config", "user.email", EMAIL)
    initial = git("rev-parse", "HEAD")
    previous = last_manifest()
    official = api(f"repos/{UPSTREAM}/releases/latest")
    tag = validate_official(official)
    # Never merge upstream/main: fetch exactly the published tag, including peeled annotated tags.
    git("fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git", f"refs/tags/{tag}")
    upstream_sha = git("rev-parse", "FETCH_HEAD^{commit}")
    if previous:
        old = previous["upstream"]
        if old["tag"] == tag and old["sha"] != upstream_sha:
            raise ValueError("Previously published upstream tag changed its commit")
        if run("git", "merge-base", "--is-ancestor", old["sha"], upstream_sha, ok=False).returncode:
            raise ValueError("Upstream release history moved backwards or was rewritten")
    merge_release(upstream_sha)
    source_tree = git("rev-parse", "HEAD^{tree}")
    plan = {"publish": False}
    if previous and source_tree == previous["source_tree"]:
        print("No code changes since the published fork build.")
    else:
        gradle = Path("app/build.gradle.kts").read_text()
        base = re.search(r'versionName\s*=\s*"(\d+\.\d+\.\d+)"', gradle)[1]
        if base != tag.removeprefix("v"):
            raise ValueError("Merged application version does not match the official release tag")
        # An upstream merge must not silently restore its updater endpoint.
        updater = Path("core/src/main/kotlin/app/toque/core/update/Releases.kt").read_text()
        if f'const val REPO = "{FORK}"' not in updater:
            raise ValueError("Fork update endpoint was lost during the upstream merge")
        git("fetch", "--tags", "origin")
        version, code = next_version(base, git("tag", "--list").splitlines(), previous)
        plan = {
            "publish": True, "repository": FORK, "tag": f"v{version}",
            "version_name": version, "version_code": code,
            "initial_commit": initial, "source_commit": git("rev-parse", "HEAD"), "source_tree": source_tree,
            "upstream": {"repository": UPSTREAM, "tag": tag, "sha": upstream_sha},
            "workflow_run": f"https://github.com/{FORK}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
            "device_tested": False,
        }
        print(f"Prepared {plan['tag']} from official {tag}. Remote main is unchanged until tests pass.")
    plan_path.parent.mkdir(parents=True, exist_ok=True)
    plan_path.write_text(json.dumps(plan, indent=2) + "\n")
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"publish={str(plan['publish']).lower()}\n")
        if plan["publish"]:
            output.write(f"version={plan['version_name']}\ncode={plan['version_code']}\n")


def verify_apk(apk, plan):
    sdk = Path(os.environ["ANDROID_HOME"])
    analyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
    for field, expected in [("application-id", "app.toque"), ("version-name", plan["version_name"]),
                            ("version-code", str(plan["version_code"]))]:
        if run(str(analyzer), "manifest", field, str(apk)).stdout.strip() != expected:
            raise ValueError(f"Built APK has incorrect {field}")
    signers = sorted(sdk.glob("build-tools/*/apksigner"))
    if not signers:
        raise ValueError("Android APK signature verifier missing")
    report = run(str(signers[-1]), "verify", "--min-sdk-version", "23", "--print-certs", str(apk)).stdout.lower()
    fingerprints = re.findall(r"certificate sha-256 digest:\s*([a-f0-9]+)", report)
    if fingerprints != [CERT_SHA256]:
        raise ValueError("Built APK does not carry the existing Toque certificate")
    # Check the compiled endpoint, not just the source or Gradle property.
    dex = run(str(analyzer), "dex", "code", "--class", "app.toque.update.Updater", str(apk)).stdout
    if f"https://api.github.com/repos/{FORK}/releases/latest" not in dex:
        raise ValueError("Compiled APK does not check the fork's releases")


def publish(plan_path):
    assert_fork_checkout()
    plan = json.loads(plan_path.read_text())
    if not plan["publish"] or plan["repository"] != FORK or git("rev-parse", "HEAD") != plan["source_commit"]:
        raise ValueError("Release plan no longer matches the tested commit")
    apk = plan_path.parent / f"Toque-{plan['version_name']}.apk"
    shutil.copyfile("app/build/outputs/apk/release/app-release.apk", apk)
    verify_apk(apk, plan)
    plan["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
    manifest = plan_path.parent / "fork-manifest.json"
    manifest.write_text(json.dumps(plan, indent=2) + "\n")
    checksums = plan_path.parent / "SHA256SUMS.txt"
    checksums.write_text(f"{plan['apk_sha256']}  {apk.name}\n")
    notes = plan_path.parent / "notes.md"
    notes.write_text(f"""Toque {plan['version_name']} — fork de Maxime Huran

Base officielle : [{plan['upstream']['tag']}](https://github.com/{UPSTREAM}/releases/tag/{plan['upstream']['tag']}).
Ce fork conserve ses fonctionnalités, dont les sources JSON configurables. Les recettes restent dans un dépôt séparé.

Mises à jour : recherche et confirmation manuelles sur le robot, depuis {FORK}. Aucune installation automatique.

Tests unitaires, migration SQLite, tests de synchronisation du fork et compilation Android 6 réussis.
Signature, identifiant, version et adresse de mise à jour de l'APK vérifiés. [Compilation]({plan['workflow_run']}).
Cette compilation n'a pas encore été validée physiquement sur le robot. Premier essai : import et affichage des recettes.

Sauvegarder les données de Toque avant l'installation initiale. La base de recettes passe en version 2 ; revenir à l'ancienne application nécessite de restaurer sa sauvegarde.
""")
    # No forced update, even if a human pushed during the build. The branch/tag push is atomic.
    remote = git("ls-remote", "origin", "refs/heads/main").split()[0]
    if remote != plan["initial_commit"]:
        raise ValueError("Remote main changed during the build; retry on its new head")
    git("tag", plan["tag"], plan["source_commit"])
    git("push", "--atomic", "origin", "HEAD:refs/heads/main", f"refs/tags/{plan['tag']}")
    # gh creates a draft, uploads all assets, then publishes. Failed uploads cannot advertise an incomplete APK.
    run("gh", "release", "create", plan["tag"], str(apk), str(manifest), str(checksums),
        "--repo", FORK, "--verify-tag", "--latest", "--title", f"Toque {plan['version_name']} — Maxime",
        "--notes-file", str(notes))
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
        summary.write(f"## Published {plan['tag']}\n\nhttps://github.com/{FORK}/releases/tag/{plan['tag']}\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "publish"])
    parser.add_argument("--plan", type=Path, required=True)
    args = parser.parse_args()
    {"prepare": prepare, "publish": publish}[args.action](args.plan)
