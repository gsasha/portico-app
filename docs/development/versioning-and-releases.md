# Version rule and releases

How `versionName` and the derived `versionCode` work, and how one merged pull request becomes a published release.

**Version scheme:** `versionName` (`app/build.gradle.kts`, `porticoVersionName`) is the only
input, `MAJOR.MINOR.PATCH[-alpha|beta|rc[.N]]`, and `versionCode` is derived from it with no git
count and no clock: `MAJOR*1000000 + MINOR*10000 + PATCH*100 + stage + N`, where stage is alpha
10, beta 30, rc 50 and a final release 90. `0.1.0-alpha` is 10010, `0.1.0` is 10090,
`0.1.1-alpha` is 10110: each later version has a larger code, as Android requires for an update.

**Releasing a version takes one PR.** The change PR itself carries the release decision: it edits
the one `porticoVersionName` line in `app/build.gradle.kts` (nothing else names the version; the README
does not). After it is merged, from a clean checkout of `main`:

```sh
git checkout main && git pull && scripts/release.sh 0.5.3-alpha   # add --dry-run to stop before publishing
```

It refuses unless HEAD is on `origin/main`, the argument equals `porticoVersionName` and no Gitea release or
tag `v<ver>` exists; builds (`gocore/bind.sh`, signed `assembleRelease`); checks the APK (`aapt2` badging,
signer SHA-256 equal to the v0.4.3 key, `libgojni`, size, sha256) and aborts before any upload on any mismatch;
then creates the prerelease `v<ver>` with one asset `portico-<ver>.apk`, downloads it back and compares.
Secrets stay outside the repo: `PORTICO_SIGNING_PROPS` (default `~/secrets/portico/signing.properties`) and the
Gitea token (`scripts/gitea.py`). `scripts/version-guard-test.sh` fails a bad version form or a `versionCode`
not above `scripts/release-history`; `release.sh` also compares with the live releases.

**Publishing target.** `scripts/release.sh` builds and verifies the APK, then uploads it to a Gitea server through `scripts/gitea.py`. Nothing
points at a default server: set `PORTICO_GITEA_URL` (the server), `PORTICO_GITEA_TOKEN` (an API token) and `PORTICO_GITEA_REPO` (`owner/name`)
in the environment (or put the first two as `KEY=value` lines in a file named by `PORTICO_GITEA_ENV`). `PORTICO_GITEA_PUBLIC_URL` only changes
the release link the script prints. Published releases of the project live on GitHub: after `release.sh --dry-run` has built and checked the
APK, attach it with `gh release create v<ver> portico-<ver>.apk --prerelease`, or replace the upload step in the script with your own. The script
also checks that the APK is signed with the project's release key (the one the first public build, 0.4.3-alpha, was signed with); a fork that
signs with its own key has to change `EXPECTED_SIGNER` in the script. The signing properties file stays outside the repository and is named by
`PORTICO_SIGNING_PROPS` (default `~/secrets/portico/signing.properties`). The token and the properties file are never committed.
