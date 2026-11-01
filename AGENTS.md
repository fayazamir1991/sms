# Project guidance

The canonical repository for this project is https://github.com/fayazamir1991/sms. Continue development against this repository. Read the current branch and remote state before making changes; preserve unrelated user changes. Do not force-push.

This is Delneveshte, a native Java Android app for personally approved romantic SMS from the user's own SIM. The current device is Xiaomi, Android 14, with two SIMs. The UI is Persian and RTL. Google Messages should remain the default SMS application.

Preserve these requirements:

- The user configures recipient, sending SIM, daily message count and allowed time window.
- Offer several texts for selection/editing. Only exact approved recipient/body/SIM snapshots can be sent. Settings changes must never redirect previously approved messages.
- Empty queue means no send. Pausing cancels scheduling. No automatic retries after an ambiguous sending result.
- Recheck the allowed window at actual scheduled execution. Restoring alarms after reboot or reopening must never send a message directly.
- Do not send real SMS from development tools. Handset testing requires the user's explicit confirmation in the app.
- Never commit local credentials, signing keystores, personal recipient settings or build caches.

Current proposals come from an offline text bank. Do not claim unlimited AI generation or actual handset/Google Messages verification without evidence.

Build with JDK 17/21, Android SDK platform 35 and build-tools 35.0.0, Gradle 8.9 and Android Gradle Plugin 8.7.3. See docs/BUILD.md. Run the relevant compile, lint and CoreChecks validation when changing behavior. No unnecessary Android dependencies.
