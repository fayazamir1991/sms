# Project guidance

The canonical repository for this project is https://github.com/fayazamir1991/sms. Continue development against this repository. Read the current branch and remote state before making changes; preserve unrelated user changes. Do not force-push.

This is Delneveshte, a native Java Android app for personally approved romantic SMS from the user's own SIM. The current device is Xiaomi, Android 14, with two SIMs. The UI is Persian and RTL. Google Messages should remain the default SMS application.

Preserve these requirements:

- The user configures recipient, sending SIM, daily message count and allowed time window.
- Offer several texts for selection/editing. Only exact approved recipient/body/SIM snapshots can be sent. Settings changes must never redirect previously approved messages.
- Tapping «افزودن به صف» is the user's approval: add directly with a toast, no extra dialog or navigation. Editing shows three explicit sending choices: immediate, random and fixed date/time. Immediate sending always requires a confirmation; cancellation must create no queued or history entry.
- Empty queue means no send. Pausing cancels scheduling. No automatic retries after an ambiguous sending result.
- Recheck the allowed window at actual scheduled execution. Restoring alarms after reboot or reopening must never send a message directly.
- Do not send real SMS from development tools. Handset testing requires the user's explicit confirmation in the app.
- Never commit local credentials, signing keystores, personal recipient settings or build caches.

Primary backend deployment is Docker on Hamravesh: Node 24, persistent SQLite under /data, one replica. Cloudflare Workers + D1 is optional and manual only. Proposals come from the server catalog, cached locally; retain the offline fallback. Queue changes and durable sync events must be atomic. Server acknowledgements must never discard newer local revisions. Preserve nondestructive DB v1/v2→v3 migration, including existing approved schedules and sync identities. Cloud synchronization is enabled by default without a confirmation dialog, as explicitly requested by the product owner. Keep the disclosure and disable control in settings. Never enable a paid hosting plan. Do not claim unlimited AI generation or actual handset/Google Messages verification without evidence.

The user requires each version to have a GitHub Release with an APK asset. Increment versionCode/versionName in app/build.gradle, add Persian notes under docs/releases/vVERSION.md, and let the successful main-branch workflow publish the version. Existing release tags/assets must not be silently overwritten. Releases remain experimental. Preserve the original chat-installed signing identity pinned in signing-certificate.sha256. Never generate a replacement signing key; refuse release publication on a mismatch. Never overwrite existing release assets.

Build with JDK 17/21, Android SDK platform 35 and build-tools 35.0.0, Gradle 8.9 and Android Gradle Plugin 8.7.3. See docs/BUILD.md. Run the relevant compile, lint and CoreChecks validation when changing behavior. No unnecessary Android dependencies.

Catalog imports must retain attribution, deduplicate excerpt text and preserve admin edits/deactivations. Full Ganjoor processing is one-time; nightly collection runs at 02:00 Asia/Tehran, cap 20/day by default. See docs/CATALOG-IMPORT.md.

Version 0.7 requirements: Namnak is excluded. Ganjoor uses balanced-v3 multi-signal personal/emotional selection plus reviewed exceptions; do not regress to a love-word match or the 24-item-only restriction. Keep policy rules, sample report and stored selection metadata auditable. Migration 0008 is the owner-authorized one-time FULL server data reset; do not repeat it on future deployments. Browse/search pages hold 20 messages and append via a signed stable cursor; SMS approval snapshots remain immutable. Source details belong only in the card overflow menu.

Version 0.9: search is a dedicated bottom-navigation tab across all categories. Typing filters immediately and debounces remote requests by 350 ms; clear restores the default list. Search combines matching cached/bundled messages with all server matches and appends 20 at a time without duplicates. Production SQLite must detect result-bearing statements using prepared-statement columns, including WITH/CTEs; test the real SQLiteStore and Docker, not only the test adapter. Home has no recipient/SIM instructions. Cards show only text and two compact random/fixed actions; tap/long press opens all actions and source details.

Fixed-time messages persist sendMode=FIXED and requestedAt, independent of random daily count/window. Global pause still stops automatic sending and retains requestedAt. Missed paused fixed messages fail instead of being randomized; scheduled alarms may execute up to 30 minutes late for OS delays. Reconcile and reboot only arm alarms, never send directly. Immediate intents use IMMEDIATE status and never enter the automatic queue. Never change approved body/recipient/SIM during planning or upgrade.

Design direction: modern messenger components inspired by the open TelegramUI library and the 2026 Telegram Android navigation, adapted to native Java and Persian RTL. The owner explicitly rejected the classic 7.8 blue appearance. The owner explicitly selected palette 1, lacquer red and gold: primary #C81D25, highlight #FF5A36, decorative gold #D4AF37, ivory background #FFFAF2. Use darker gold #8D6D13 for functional icons/text on light surfaces. Keep restrained static edge glints and soft depth with readable labels. Never revert to the old pink or blue brand. Sources, tokens and the distinction between the inaccessible original Figma layers and the inspected open component sources are documented in docs/DESIGN-SYSTEM.md. Bundle Vazirmatn with OFL notices. Preserve two card actions, global live search, all three sending modes, the signing identity and the existing SQLite schema.

Navigation and action icons now use Font Awesome Free 7.3.1 Classic Solid, adapted from the original SVG paths into VectorDrawable. Preserve CC BY 4.0 attribution, original source manifest and bundled license; the settings credits dialog exposes licensing to users. No paid icon pack or icon-font runtime dependency is needed.
