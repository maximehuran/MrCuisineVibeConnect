#!/usr/bin/env python3
"""Release selection, monotonic versions, real Git merges and publication failure isolation."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("fork_release", Path(__file__).parent / "fork/release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleasePolicyTest(unittest.TestCase):
    def official(self, **changes):
        return dict(tag_name="v0.5.1", draft=False, prerelease=False, published_at="2026-10-25T00:00:00Z",
                    assets=[{"name": "Toque-0.5.1.apk"}], **changes)

    def test_only_published_stable_releases(self):
        original = self.official()
        self.assertEqual("v0.5.1", release.validate_official(original))
        for changes in [{"draft": True}, {"prerelease": True}, {"published_at": None},
                        {"tag_name": "v0.5.2-rc1"}, {"tag_name": "main"},
                        {"tag_name": "v0.5.1\n--force"}, {"assets": []}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                release.validate_official(dict(original, **changes))

    def test_fork_sequence_survives_upstream_version_changes_and_abandoned_tags(self):
        self.assertEqual(("0.5.1.1", 10001), release.next_version("0.5.1", ["v0.5.1", "v0.5.0"]))
        old = {"version_name": "0.5.1.2", "version_code": 10002}
        self.assertEqual(("0.5.2.4", 10004), release.next_version("0.5.2", ["v0.5.1.2", "v0.5.1.3"], old))
        with self.assertRaises(ValueError):
            release.next_version("0.5.0", ["v0.5.1.2"], old)
        with self.assertRaises(ValueError):
            release.next_version("0.5.2", [], old)

    def test_refuses_publishing_from_a_feature_branch_or_another_repository(self):
        for repo, branch in [(release.FORK, "refs/heads/feat/json-recipe-sources"),
                             (release.UPSTREAM, "refs/heads/main")]:
            with patch.dict(os.environ, GITHUB_REPOSITORY=repo, GITHUB_REF=branch), self.assertRaises(ValueError):
                release.assert_fork_checkout()


class GitIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous_dir = Path.cwd()
        os.chdir(self.tmp.name)
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        Path("shared.txt").write_text("base\n")
        self.commit("base")
        self.base = self.git("rev-parse", "HEAD")

    def tearDown(self):
        os.chdir(self.previous_dir)
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.check_output(["git", *args], stderr=subprocess.PIPE, text=True).strip()

    def commit(self, message):
        self.git("add", ".")
        self.git("commit", "-m", message)

    def test_merge_keeps_features_and_ignores_unreleased_upstream_commits(self):
        self.git("switch", "-c", "upstream")
        Path("official.txt").write_text("published\n")
        self.commit("official release")
        stable = self.git("rev-parse", "HEAD")
        Path("unreleased.txt").write_text("not ready\n")
        self.commit("unreleased upstream change")
        self.git("switch", "main")
        Path("personal.txt").write_text("keep my feature\n")
        self.commit("personal feature")
        release.merge_release(stable)
        self.assertTrue(Path("personal.txt").exists())
        self.assertTrue(Path("official.txt").exists())
        self.assertFalse(Path("unreleased.txt").exists())
        self.assertEqual(3, len(self.git("rev-list", "--parents", "-1", "HEAD").split()))

    def test_conflict_aborts_without_advancing_main(self):
        self.git("switch", "-c", "upstream")
        Path("shared.txt").write_text("upstream\n")
        self.commit("official change")
        stable = self.git("rev-parse", "HEAD")
        self.git("switch", "main")
        Path("shared.txt").write_text("custom\n")
        self.commit("personal change")
        before = self.git("rev-parse", "HEAD")
        with self.assertRaisesRegex(RuntimeError, "conflicts"):
            release.merge_release(stable)
        self.assertEqual(before, self.git("rev-parse", "HEAD"))
        self.assertEqual("", self.git("status", "--porcelain"))
        self.assertEqual("custom\n", Path("shared.txt").read_text())

    def test_invalid_apk_never_pushes_a_tag_or_creates_a_release(self):
        apk = Path("app/build/outputs/apk/release/app-release.apk")
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b"not an apk")
        plan = {"publish": True, "repository": release.FORK, "tag": "v0.5.1.1", "version_name": "0.5.1.1",
                "source_commit": self.git("rev-parse", "HEAD")}
        plan_path = Path("plan.json")
        plan_path.write_text(json.dumps(plan))
        with patch.dict(os.environ, GITHUB_REPOSITORY=release.FORK, GITHUB_REF="refs/heads/main"), \
                patch.object(release, "verify_apk", side_effect=ValueError("bad signature")), \
                patch.object(release, "run", wraps=release.run) as commands:
            with self.assertRaisesRegex(ValueError, "bad signature"):
                release.publish(plan_path)
            self.assertFalse(any(c.args[:2] in [("git", "push"), ("git", "tag"), ("gh", "release")]
                                 for c in commands.call_args_list))
        self.assertEqual("", self.git("tag", "--list"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
