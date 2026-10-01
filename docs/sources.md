# Where the Telegram patches come from

HushTelegram started on 2026-09-30. Before writing a patch we went through every public patch source we could find for Telegram (`org.telegram.messenger`, `org.telegram.messenger.web` and `org.telegram.messenger.beta`), the Xposed modules that hook it, and the forks built from its code. This page is what we found and what we took from each.

The short version: there are five Morphe or ReVanced patch sets for Telegram, and four of them are one family. Morphe's official bundle and ReVanced ship nothing for Telegram. None of the four patches HushTelegram has today took code from any of them.

This page is the readable version. The one the scripts hold us to is [sources/telegram-sources.json](../sources/telegram-sources.json), which records every source with its branches and the commits we last read them at, its licence and a hash of the licence text, the Telegram builds it declares, its features, and what we're allowed to take from it.

## How a source gets in

Each source in the ledger gets one of four answers:

- **adopted** means HushTelegram ships code from it. No Telegram source is adopted today.
- **candidate** means its licence lets us port code, and we might, once the checks below pass.
- **behavior-only** means we never port its code. We can read what it does and find the same thing in Telegram's own code, and that's all.
- **rejected** means it's licensed but there's nothing there to take.

A source with no licence, or one whose licence can't be combined with GPL-3.0, is behavior-only. So is a source whose Telegram code came from one of those. Before code from a source can ship, the ledger needs the commit it came from, a compatible licence with its URL and hash, the source's name in [NOTICE](../NOTICE), a rule in [provenance.json](../provenance.json) naming that repository and commit, and a release receipt showing the patches applied to the Telegram builds the bundle declares.

HushTelegram's own code starts from [HushThreads](https://github.com/SysAdminDoc/HushThreads) at commit b141524, the Threads sibling in the same family. The settings screen, the diagnostics, the pause switch, the release check and the patch-time safety checks all come from there, and every file that did says so in its header. That's family code, not a Telegram source, so it's recorded in provenance.json and NOTICE rather than here.

## The Morphe and ReVanced sets

**[MoonShadowKeeper/Telegram-patchesMorphe](https://github.com/MoonShadowKeeper/Telegram-patchesMorphe)** (GPL-3.0) is the oldest. Its Telegram history starts on 2026-04-17, and it has ten patches for telegram.org's 12.8.3: Remove ads, Disable auto update, Download speed boost and Hide typing indicator, plus Premium, integrity, restriction and anti-delete patches. Its folder layout (ads, content, premium) and most of its patch names turn up four months later in rushiranpise's bundle, so we count it as the root of that family.

**[rushiranpise/morphe-patches](https://github.com/rushiranpise/morphe-patches)** (GPL-3.0) is the most complete live set, inside a large bundle for many apps. It has fifteen Telegram patches for 12.10.1, on both packages. The part we care about most is its signature chain. It plants Telegram's own certificate in the patched app and answers both Telegram's fingerprint check and the certificate hash Firebase sends with it. Its own history shows that getting the certificate format right (the bare X.509 certificate, not the PKCS7 block around it) is what fixed a Firebase 403 for them. We got exactly that 403 on a phone with a patched 12.10.6, so that chain is the first port to weigh.

**[newuser7171/telegram-morphe-patches-](https://github.com/newuser7171/telegram-morphe-patches-)** (GPL-3.0) is rushiranpise's Telegram set carried on under a third name, retargeted to 12.10.4 and hardened so every target has to exist. It earlier lived at cingxcong and rahul9999xda, both gone now. Its Remove ads looks in the same two places our Hide ads found on its own in 12.10.6: the messages controller's sponsored messages request and the video ads loader.

**[vomw/morphe-patches](https://github.com/vomw/morphe-patches)** (GPL-3.0) is a one-time copy of rushiranpise's set from August, taken before the certificate fix. It's rejected, since the live bundle does everything it does, better.

**[Aunali321/ReVancedExperiments](https://github.com/Aunali321/ReVancedExperiments)** (GPL-3.0) was written separately, in the older ReVanced style, and has been quiet since March. Its six Telegram patches declare no build. It reached the same signature and integrity anchors as the Paresh family on its own, which is good evidence those are the right places.

**[Hari-sys786/telegram-patches](https://github.com/Hari-sys786/telegram-patches)** (GPL-3.0) is a separate set verified against 12.10.1, with fingerprints that fail loudly instead of patching the wrong method. Disable story read marking and Remove promo channels are the two ideas worth checking against 12.10.6. Most of its other patches unlock Premium or get past content protection.

## Xposed modules

**[Nep-Timeline/Re-Telegram](https://github.com/Nep-Timeline/Re-Telegram)** (GPL-3.0) hooks official Telegram and a long list of forks at run time. Hide Stories and Prohibit channel switching are the ideas to rewrite as patches. A port here means writing the idea again against Telegram's code, not copying hooks. hxreborn's Re-Telegram-CherryFork is a fork of it that adds Cherrygram.

**[shatyuka/Killergram](https://github.com/shatyuka/Killergram)** (GPL-3.0) exists mainly to remove sponsored messages, and it's the best measure of how much people want them gone. [AetherMagee/KillergramNeo](https://github.com/AetherMagee/KillergramNeo) is a smaller rewrite of it.

[cinit/TMoe](https://github.com/cinit/TMoe) is recorded as out of scope, since we couldn't confirm it hooks official Telegram. Showing message IDs and turning off the swipe between channels are its ideas.

## Forks

Telegram's Android source is public under GPL-2.0-or-later, and a lot of forks build on it: Nekogram, Nagram, NagramX, Nnngram, Cherrygram, OctoGram, Forkgram, Momogram and older ones like exteraGram, AyuGram's Android port and OwlGram. They're whole apps with their own packages, so they aren't patch sources and the ledger lists them as out of scope. They're still the best list of what people want: Hide Stories, hiding the typing and online status, hiding similar-channel suggestions, opening links in your own browser and a faster downloader all come up again and again.

Two public write-ups report Nekogram sending users' phone numbers to its developer, so its network and account code is never a reference here. Plus Messenger (`org.telegram.plus`) hasn't published its source since 2017 and has been documented doing harmful things. Several patch sets above support it. HushTelegram never will.

## What we won't take

Premium unlocks, getting past a channel's forward or save protection, and opening content Telegram hides for age or legal reasons are all popular, and the sets above ship them. HushTelegram doesn't, whatever the licence says. Raising the account limit past what Telegram allows is in the same group, since more accounts is a Premium perk.

## Where HushTelegram is listed

Nowhere. The repository is private for now, and none of the Morphe indexes (the community directory, Awesome Morphe, the Morphe Patch Tracker, Jman's bundle index and the Morphe Archive) list it.
