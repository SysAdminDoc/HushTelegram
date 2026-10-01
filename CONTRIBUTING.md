# Contributing

Bug reports, fixes for a new Telegram build, new patches and pull requests are all welcome.

If you open an issue, include:

- the Telegram version you patched and where it came from (telegram.org's APK, for now)
- the Morphe Manager version and the HushTelegram version
- the patches you selected
- what you expected and what happened, with steps to get there
- a diagnostic report or screenshots if it's visual or a crash. Remove private messages, phone numbers and account details from screenshots first.

GitHub doesn't let the person who opened an issue reopen it once a maintainer closes it, so a closing comment always says how to get it reopened: comment there and we'll reopen it.

## What we won't add

Patches that unlock Premium, get past a channel's forward or save protection, open content Telegram hides for age or legal reasons, or raise the account limit won't be merged, whatever their licence. The same goes for code copied from a project that does those things, unless the copied part is something else entirely and its source is listed as allowed in `sources/telegram-sources.json`.

## When Telegram updates

Telegram ships a new version every week or two. Most of `org.telegram.messenger` and every TL class in `org.telegram.tgnet.TLRPC` keep their names in the APK, while `org.telegram.ui` is renamed by R8 in every build. A patch that finds what it needs by a TL request it builds, a field it reads or a kept method it calls usually carries over. A patch that doesn't fails at patch time with a message saying what it couldn't find. Fixing it for the new build goes like this:

1. Download the new APK from telegram.org and put it in your fixture folder.
2. Run `scripts/verify-all-patches.ps1 -Apk <new apk> -Force -DesktopJar <jar> -WorkDir <scratch>`. `-Force` lets the CLI patch a version the bundle doesn't declare yet, and the result names every patch that failed.
3. Find where the failing patch's anchor went (the tool below helps), change the patch to find it on both the new build and the ones already declared, and never write down a name R8 gave one build: `ObfuscatedIdentityTest` fails on one. Lambda numbers such as `lambda$getSponsoredMessages$12` change too.
4. Add the build to `AppCompatibilities.kt` with its version code, regenerate the patch list, and run the checks again on every retained build.

For a patch change, say which Telegram build you tested against and what you checked.

### Finding where a method went

`scripts/fingerprint-candidates.ps1` ranks the methods of the new build by how much each one looks like the method the patch found on the old one. Give it the method as the old build names it and both builds, as a path or as a version your fixture folder has:

```powershell
scripts/fingerprint-candidates.ps1 -OldApk 12.10.6 -Method 'Lorg/telegram/ui/LaunchActivity;->...(Z)V' -NewApk <new apk>
```

It only compares what survives a rebuild: the strings a method loads, its literals, the framework and kept-class calls it makes, an opcode sketch, its prototype, its class and who calls it. The report lists the five closest methods and sets each one beside the old method. The tool changes nothing. When one candidate is clearly ahead it says so and still leaves the patch to you. When two are close, or none scores well enough, it exits 1 and names no candidate.

## Building and checking

Read the README's build section first. Gradle needs `GITHUB_ACTOR` and `GITHUB_TOKEN` (a token with `read:packages`) to fetch the Morphe patcher. Run `:patches:generatePatchesList` before `:patches:buildAndroid`. A test run afterwards replaces the jar in `patches/build/libs`, which is why the release bundle is copied to `patches/build/release`.

The checks that matter before a release:

- `:patches:test` and `:extensions:telegram:testDebugUnitTest`, with `HUSHTELEGRAM_FIXTURE_DIR` set so the tests that read real Telegram builds run instead of skipping.
- `scripts/verify-all-patches.ps1` on every retained fixture. It applies all patches in one run, checks the CLI's own report and holds the rebuilt resource table to the original's.
- `scripts/build-release-receipt.ps1`, which writes the release receipt from those runs, and `scripts/validate-release-facts.ps1`, which holds the README, the CHANGELOG and the bug form to the generated patch list.
- The advisory check inside the receipt script. It reads the SBOM `buildAndroid` writes beside the bundle and asks [OSV](https://osv.dev) about every library in it, and a high or critical advisory stops the release. If one doesn't apply to what the bundle does with that library, accept it in `scripts/advisory-exceptions.txt` with the reason and a date at most 90 days out.

`scripts/install-hooks.ps1` installs a pre-push hook that runs the tests when a push changes `extensions/` or `patches/`, and the release check when it changes a published file. Set `HUSHTELEGRAM_SKIP_PRE_PUSH=1` to push without it.

## Settings for your machine

Nothing in the repository points at a folder or a phone on anybody's machine. These variables do that instead, and none of them has a default:

- `HUSHTELEGRAM_FIXTURE_DIR` is the folder holding the Telegram builds the fixture tests and scripts read. They're over sixty megabytes each, so they aren't in the repository.
- `HUSHTELEGRAM_DESKTOP_JAR` is the Morphe desktop CLI jar. `HUSHTELEGRAM_WORKDIR` or a jar under `build/morphe-tools` works too.
- `HUSHTELEGRAM_BUILD_WRAPPER` names a PowerShell script the pre-push hook runs Gradle through, called as `<wrapper> -ProjectDir <repository> -Tasks <task>...`. Unset, the hook runs `gradlew.bat` itself.
- `HUSHTELEGRAM_DEVICE_SERIAL` is the adb serial of a test phone for `scripts/patch-for-device.ps1`. Keep your own phone out of it: a patched Telegram can't install over the stock app without uninstalling it, which signs you out and drops your secret chats.

## Source notices

Keep every existing copyright, license, author credit and source-origin notice when you modify or move a file, and don't remove a notice unless the code it covers is gone from the file. Most of the code here came from HushThreads, which took it from Hushfacebook, and some of that from Andrew Liang's patches and FroggoMorphePatches, all GPL-3.0. In this ecosystem a missing notice has already ended in DMCA takedowns more than once.

New source written for this project may use:

```text
/*
 * Copyright <year> HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
```

Code taken from another project keeps its notices and gets a `Forked from` line naming the project and the commit it came from. Record it in `provenance.json` too. A rule naming a single file wins over the folder rule around it, which is how a file written here can sit among ported code. `ProvenanceTest` fails when a shipped file matches no rule or two, or when a rule names an upstream that NOTICE doesn't. It also holds every header to its rule. The header has to link a repository of that rule's chain, and each `Forked from` source has to be one of them. A file under a rule for code written here can't say it came from anywhere, however it words that, and every rule has to state its licence.

Code from outside the Hush family can only come from a source that `sources/telegram-sources.json` lists as adopted. That takes the commit the code came from, a licence that works with GPL-3.0, the source in NOTICE, its rule in `provenance.json`, and a release receipt showing the Telegram fixtures patched. `scripts/test-telegram-sources.ps1` refuses the ledger without any of them. A source the ledger calls behavior-only is never copied from, only read for what it does. When you find a new source, run `scripts/audit-telegram-sources.ps1`, which reports what moved and stamps the census once nothing has. A release won't go out on a census more than 14 days old.
