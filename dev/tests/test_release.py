"""Offline regressions for release selection; never create a public test release."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def fixture(self, code, version, draft=False):
        data = dict(schema_version=1, available=True, package="org.childgram", version=version, version_code=code,
                    file_url=f"{release.RELEASES}/download/v{version}/childgram-{version}-arm64.apk",
                    size=100, sha256="a" * 64, changelog="Тест", commit="b" * 40,
                    certificate_sha256=(release.ROOT / "dev/release-certificate.sha256").read_text().strip())
        url = f"{release.RELEASES}/download/v{version}/android.json"
        item = dict(tag_name="v" + version, draft=draft, prerelease="-" in version, assets=[
            dict(name="android.json", browser_download_url=url),
            dict(name=f"childgram-{version}-arm64.apk", browser_download_url=data["file_url"], size=100,
                 digest="sha256:" + data["sha256"])])
        return item, data

    def test_empty_and_drafts(self):
        self.assertEqual((None, None), release.select_release([]))
        draft, _ = self.fixture(100, "1.0.0", True)
        self.assertEqual((None, None), release.select_release([draft], lambda _: self.fail("Draft fetched")))

    def test_published_prereleases_order_by_code(self):
        items, manifests = [], {}
        for code, name in [(9, "0.1.0-alpha.9"), (10, "0.1.0-alpha.10"), (8, "99.0.0")]:
            item, data = self.fixture(code, name)
            items.append(item)
            manifests[item["assets"][0]["browser_download_url"]] = data
        self.assertEqual(10, release.select_release(items, manifests.__getitem__)[0]["version_code"])

    def test_broken_publication_fails(self):
        item, data = self.fixture(1, "0.1.0-alpha.1")
        for field, value in [("version_code", True), ("version_code", 2147483648), ("size", 0),
                             ("file_url", "https://evil.example/app.apk"), ("package", "org.telegram.messenger"),
                             ("sha256", "invalid"), ("certificate_sha256", "a" * 64)]:
            with self.subTest(field=field, value=value), self.assertRaises(AssertionError):
                release.validate(dict(data, **{field: value}))
        malformed = copy.deepcopy(item)
        malformed["assets"] = []
        with self.assertRaises(AssertionError):
            release.select_release([malformed], lambda _: data)
        malformed = copy.deepcopy(item)
        malformed["assets"][1]["size"] += 1
        with self.assertRaises(AssertionError):
            release.select_release([malformed], lambda _: data)
        with self.assertRaises(AssertionError):
            release.select_release([item, item], lambda _: data)
        with self.assertRaises(AssertionError):
            release.select_release([item], lambda _: dict(data, local_fixture=True))

    def test_asset_digest(self):
        item, data = self.fixture(1, "0.1.0-alpha.1")
        release.verify_asset(data, item["assets"][1])
        with self.assertRaises(AssertionError):
            release.verify_asset(data, dict(item["assets"][1], digest="sha256:" + "b" * 64))


if __name__ == "__main__":
    unittest.main()
