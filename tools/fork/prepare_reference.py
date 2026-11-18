#!/usr/bin/env python3
"""Build a reference from an exact official tag plus an audited, small distribution layer."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).parent))
from release import api, validate_official, git, UPSTREAM

SHARED = [
    "core/src/main/kotlin/app/toque/core/update/Releases.kt",
    "core/src/main/kotlin/app/toque/core/update/Editions.kt",
    "core/src/main/kotlin/app/toque/core/update/UpdateTrust.kt",
    "core/src/test/kotlin/app/toque/core/EditionsTest.kt",
    "core/src/test/kotlin/app/toque/core/UpdateTrustTest.kt",
    "core/src/test/resources/fixtures/expired-dst-root-x3.pem",
    "app/src/main/kotlin/app/toque/update/Updater.kt",
    "app/src/main/kotlin/app/toque/update/EditionStorage.kt",
    "app/src/main/kotlin/app/toque/update/UpdateHttps.kt",
    "app/src/main/kotlin/app/toque/ui/screens/EditionScreen.kt",
    "app/src/main/assets/update-cas/isrg-root-x1.pem",
    "app/src/main/assets/update-cas/sectigo-root-e46.pem",
]
PATCHED = ["app/build.gradle.kts", "app/src/main/AndroidManifest.xml", "app/src/main/kotlin/app/toque/Prefs.kt",
           "app/src/main/kotlin/app/toque/recipes/RecipeRepo.kt", "app/src/main/kotlin/app/toque/ui/screens/SettingsScreen.kt",
           "app/src/main/assets/lang/en.json"]


def once(text, before, after):
    if text.count(before) != 1:
        raise ValueError(f"Upstream distribution hook changed: {before[:70]!r}; review the reference patch")
    return text.replace(before, after, 1)


def block(text, start, end):
    a = text.index(start)
    b = text.index(end, a) + len(end)
    return text[a:b]


def patch_reference(directory):
    """Keep recipe/favorite methods from upstream verbatim; edit only their file-name expressions."""
    for relative in SHARED:
        target = directory / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / relative, target)
    gradle = directory / "app/build.gradle.kts"
    extra = block((ROOT / "app/build.gradle.kts").read_text(), "// BEGIN DISTRIBUTIONS", "// END DISTRIBUTIONS")
    gradle.write_text(gradle.read_text() + "\n" + extra + "\n")
    manifest = directory / "app/src/main/AndroidManifest.xml"
    manifest.write_text(once(manifest.read_text(), "        <activity\n",
        '        <meta-data android:name="app.toque.edition" android:value="${toqueEdition}" />\n\n        <activity\n'))
    prefs = directory / "app/src/main/kotlin/app/toque/Prefs.kt"
    prefs.write_text(once(prefs.read_text(), 'context.getSharedPreferences("toque", Context.MODE_MULTI_PROCESS)',
                         'app.toque.update.EditionStorage.preferences(context)'))
    repo = directory / "app/src/main/kotlin/app/toque/recipes/RecipeRepo.kt"
    repo.write_text(once(once(repo.read_text(), 'SQLiteOpenHelper(context, "recettes.db",',
                       'SQLiteOpenHelper(context, app.toque.update.EditionStorage.databaseName,'),
                       'File(context.filesDir, "images")', 'File(context.filesDir, app.toque.update.EditionStorage.imagesDirectory)'))
    settings = directory / "app/src/main/kotlin/app/toque/ui/screens/SettingsScreen.kt"
    s = once(settings.read_text(), '        renderUpdate()\n\n        // ---- Outils',
        '        renderUpdate()\n        updates.addView(link(tr("Version de l’application")) { a.push(EditionScreen(a)) }, Ui.lp(t = 12))\n\n        // ---- Outils')
    a = s.index("    /** Propose l'installation dans une fenêtre")
    b = s.index("    override fun onShow()", a)
    common = block((ROOT / "app/src/main/kotlin/app/toque/ui/screens/SettingsScreen.kt").read_text(),
                   "    // BEGIN MANAGED INSTALL", "    // END MANAGED INSTALL")
    settings.write_text(s[:a] + common + "\n\n" + s[b:])
    en = directory / "app/src/main/assets/lang/en.json"
    translations = json.loads(en.read_text())
    translations.update(json.loads((ROOT / "tools/fork/edition-strings.json").read_text()))
    en.write_text(json.dumps(translations, ensure_ascii=False, indent=2) + "\n")


def prepare(directory, sha=None):
    if directory.exists():
        raise ValueError("Reference directory must not already exist")
    if sha is None:
        tag = validate_official(api(f"repos/{UPSTREAM}/releases/latest"))
        git("fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git", f"refs/tags/{tag}")
        sha = git("rev-parse", "FETCH_HEAD^{commit}")
    if not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise ValueError("Expected a full official commit SHA")
    directory.mkdir(parents=True)
    archive = subprocess.Popen(["git", "archive", sha], cwd=ROOT, stdout=subprocess.PIPE)
    subprocess.run(["tar", "-x", "-C", str(directory)], stdin=archive.stdout, check=True)
    archive.stdout.close()
    if archive.wait():
        raise RuntimeError("Official source archive failed")
    before = {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in directory.rglob("*") if p.is_file()}
    patch_reference(directory)
    after = {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in directory.rglob("*") if p.is_file()}
    changed = {p for p in set(before) | set(after) if before.get(p) != after.get(p)}
    if not changed <= set(SHARED + PATCHED):
        raise ValueError("Unexpected reference changes: " + str(changed - set(SHARED + PATCHED)))
    provenance = {"upstream_sha": sha, "changed_files": {
        p: {"before": before.get(p), "after": after.get(p)} for p in sorted(changed)}}
    (directory.parent / "reference-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(f"Reference prepared from {sha}; {len(changed)} audited distribution files; upstream favorites code retained.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--sha")
    args = parser.parse_args()
    prepare(args.directory.resolve(), args.sha)
