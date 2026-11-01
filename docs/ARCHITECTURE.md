# Architecture

The Android app keeps its approved queue locally and can opt into HTTPS server synchronization. MainActivity provides four Persian RTL views: suggestions, approved queue, send history, and settings. The server supplies a catalog cached on the phone; TextBank remains the fallback until the first catalog download. Server responses cannot enqueue or send an SMS.

Settings and install credentials are stored in app-private preferences. AppStore owns SQLite records with immutable approved body, destination and subscription ID. Database migration v1 to v2 adds synchronization metadata without deleting existing messages. Queue state changes, revisions and durable outbox events commit together; multipart callbacks are deduplicated by message and part index. JobScheduler uploads pending snapshots when network and Android scheduling permit. Only matching acknowledgements remove transmitted events, so concurrent newer local changes remain pending.

Flow: the saved recipient and SIM appear above suggestions. The primary add-to-queue button approves the exact visible text in one tap without navigating away; editing opens one dialog whose save adds directly. Immediate sending has a separate confirmation. Cancelling it writes nothing. Newly confirmed immediate sends use an IMMEDIATE state that is excluded from the automatic queue, including after interruptions. SchedulePlanner chooses ordered random times inside the daily window. SmsRuntime uses one-shot inexact alarms and checks actual time, permissions, active subscription and daily quota before claiming a message. It sends via ordinary SmsManager so Android can persist the outgoing SMS for the default SMS app. Google Messages visibility remains a device acceptance test.

Successful submission to the carrier does not prove delivery. Ambiguous outcomes remain uncertain and are never retried automatically. A manual send requires a separate confirmation in the UI. Boot, clock changes and reopening restore scheduling without directly sending.

No automatic backup or device transfer of preferences and queued messages is configured. Runtime permission availability and Xiaomi battery/autostart restrictions must be verified on the handset. See the Persian installation guide.

## Backend deployment

The primary deployment is the root Dockerfile on Hamravesh: Node 24, native HTTP server and persistent SQLite under /data. No runtime npm dependencies or separate database service are required. A single replica and a persistent disk with standard local SQLite file locking are required; horizontal scaling needs a different database architecture. PUBLIC_URL supplies the externally visible HTTPS origin behind the platform TLS proxy.

The first startup applies transactional migrations and a seed of 1009 distinct messages. Migration history is stored on the persistent disk; replacement containers do not reseed or overwrite admin edits. The same query interface supports optional Cloudflare D1 deployment, now manual only.

The Persian admin exposes catalog editing and read-only install, queue, event and sent-log inspection. Sessions use Secure HttpOnly SameSite cookies, origin validation and CSRF tokens. Private approved message bodies and recipients use AES-GCM at rest. Preserve runtime keys across deployments; no key or admin password is generated implicitly at startup. Installation credentials are stored as hashes on the server. A random installation ID plus an app-scoped Android ID hash support installation/device counts after online registration, without MAC or IMEI access.

The GitHub Docker checks build a real image, exercise the HTTP/admin API behind an external HTTPS origin, and replace a container while retaining its named volume. They verify catalog contents, admin edits and encrypted sent logs survive. This is independent of actual deployment to the owner's hosting account. See HAMRAVESH.md for setup and SIGNING.md for APK update compatibility.
