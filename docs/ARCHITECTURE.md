# Architecture

The app runs locally, with no server or Internet permission. MainActivity provides four Persian RTL views: suggestions, approved queue, send history, and settings. TextBank supplies offline messages and attributed public-domain poetry.

Settings are stored in app-private preferences. AppStore owns SQLite records with immutable approved body, destination and subscription ID. Queue state changes use conditional updates; multipart callbacks are deduplicated by message and part index.

Flow: the saved recipient and SIM appear above suggestions. The primary add-to-queue button approves the exact visible text in one tap without navigating away; editing opens one dialog whose save adds directly. Immediate sending has a separate confirmation. Cancelling it writes nothing. Newly confirmed immediate sends use an IMMEDIATE state that is excluded from the automatic queue, including after interruptions. SchedulePlanner chooses ordered random times inside the daily window. SmsRuntime uses one-shot inexact alarms and checks actual time, permissions, active subscription and daily quota before claiming a message. It sends via ordinary SmsManager so Android can persist the outgoing SMS for the default SMS app. Google Messages visibility remains a device acceptance test.

Successful submission to the carrier does not prove delivery. Ambiguous outcomes remain uncertain and are never retried automatically. A manual send requires a separate confirmation in the UI. Boot, clock changes and reopening restore scheduling without directly sending.

No automatic backup or device transfer of preferences and queued messages is configured. Runtime permission availability and Xiaomi battery/autostart restrictions must be verified on the handset. See the Persian installation guide.
