# Childgram development

- This is the official Telegram Android source, with a local Childgram development
  configuration. Read `dev/README.md` before changing build or emulator setup.
- Build with `./dev/build` in Docker. Do not install host Java, Gradle, NDK or SDK
  packages when the Docker environment already provides them.
- Developer API credentials, signing key and AVD data live under ignored `.local/`.
  Never print, commit or copy filled credentials into documentation or images.
- `./dev/build --smoke` explicitly permits Telegram's public sample API credentials
  for local testing. It does not produce a distributable release.
- Use `./dev/emulator` for the dedicated `childgram-api34` AVD at `emulator-5580`.
  Do not operate another emulator or physical phone implicitly.
- The development app is `org.childgram.messenger.beta`, ARM64, `afatDebug`.
  Keep its account type and provider authorities independent of official Telegram.
- The signed release candidate is `org.childgram`, ARM64, `afatRelease`; build it
  with `./dev/build --release`. Use `./dev/emulator --release install|smoke`.
  The release key is in ignored `.local/signing/`; never regenerate it after loss.
  Its public certificate fingerprint is pinned in `dev/release-certificate.sha256`.
- Preserve the normal upstream configuration outside `-PchildgramDev`/`-Pchildgram`.
  Prefer small conditional changes that are easy to merge with upstream updates.
- Childgram release updates use `https://update.childgram.org/android.json`.
  Keep drafts out of the feed and verify APK package, version, hash and the
  installed signing certificate before offering installation. Dev never updates
  itself to the release package. Release workflows prepare drafts for human review.
- For app changes, build, install and run `./dev/emulator smoke`; inspect the
  screenshot and logs. A successful build alone does not establish runtime success.
- Keep pinned submodule revisions. Use `git submodule update --init --recursive
  --depth=1` after intentional upstream updates, not `--remote`.
- The official remote is `upstream` (`DrKLO/Telegram`). The publishing remote is
  `origin` (`Childgram/cg-android`), a GitHub fork of upstream. The first public
  prerelease is `v0.1.0-alpha.1` (version code 1). Leave commits and pushes for
  the user's explicit request.
