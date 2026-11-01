# Architecture

The app runs locally, with no server or Internet permission. MainActivity provides four Persian RTL views: suggestions, approved queue, send history, and settings. TextBank supplies offline messages and attributed public-domain poetry.

Settings are stored in app-private preferences. AppStore owns SQLite records with immutable approved body, destination and subscription ID. Queue state changes use conditional updates; multipart callbacks are deduplicated by message and part index.

Flow: user selects or edits a candidate, reviews destination and SIM, explicitly approves, then the message enters the queue. SchedulePlanner chooses ordered random times inside the daily window. SmsRuntime uses one-shot inexact alarms and checks actual time, permissions, active subscription and daily quota before claiming a message. It sends via ordinary SmsManager so Android can persist the outgoing SMS for the default SMS app. Google Messages visibility remains a device acceptance test.

Successful submission to the carrier does not prove delivery. Ambiguous outcomes remain uncertain and are never retried automatically. A manual send requires a separate confirmation in the UI. Boot, clock changes and reopening restore scheduling without directly sending.

No automatic backup or device transfer of preferences and queued messages is configured. Runtime permission availability and Xiaomi battery/autostart restrictions must be verified on the handset. See the Persian installation guide.
