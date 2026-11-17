import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
TELEGRAM_13_2_2_MERGE = "05ce6279f3fc13f6b8c2bee9c2d8c0ea33290235"


class CheckUpstreamTest(unittest.TestCase):
    def test_telegram_13_2_2(self):
        with tempfile.TemporaryDirectory() as directory:
            # Share the local history and move only the temporary repository's HEAD.
            subprocess.run(["git", "clone", "--quiet", "--bare", "--shared", str(ROOT), directory], check=True)
            env = {**os.environ, "GIT_DIR": directory}
            target = TELEGRAM_13_2_2_MERGE + "^2"
            for head, expected_status, overlaps, conflicts in [
                (TELEGRAM_13_2_2_MERGE + "^1", 1, 15, [
                    "TMessagesProj/build.gradle",
                    "TMessagesProj/src/main/java/org/telegram/utils/twidget/WidgetSandboxSession.java",
                ]),
                (TELEGRAM_13_2_2_MERGE, 0, 0, []),
            ]:
                with self.subTest(head=head):
                    subprocess.run(["git", "update-ref", "--no-deref", "HEAD", head], env=env, check=True)
                    result = subprocess.run([ROOT / "dev/check-upstream", target], env=env, capture_output=True, text=True)
                    self.assertEqual(result.returncode, expected_status, result.stderr)
                    lines = result.stdout.splitlines()
                    start = lines.index(f"Changed on both sides ({overlaps}):") + 1
                    end = lines.index(f"Conflicting paths ({len(conflicts)}):")
                    shared = {line.strip() for line in lines[start:end]}
                    self.assertEqual(len(shared), overlaps)
                    self.assertTrue(set(conflicts) <= shared)
                    self.assertEqual([line.strip() for line in lines[end + 1:]], conflicts)


if __name__ == "__main__":
    unittest.main()
