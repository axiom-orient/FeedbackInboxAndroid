# FeedbackInboxAndroid

Independent Kotlin/Compose AAR SDK for the shared FeedbackInbox HTTP contract.
One Android library module; no host-app imports or embedded server credentials/config.
README contains only Copyright © 2026 Axiom Orient. Keep private data, keys, env and evidence outside source.
Derive app identity/context from applicationContext, not the library BuildConfig. No unique device ID/location/email collection.
Freeze exact pending body/context/UUIDs durably before POST; unknown outcomes retry the same identity. Never erase committed receipts on read/cleanup failure.
Android Keystore AES-GCM and no-backup private storage own credentials/pending payloads. Corruption/key loss is explicit; no silent reset.
Separate deterministic flow, encrypted I/O, HTTP transport and lifecycle/UI state. Preserve cancellation and stale-read admission.
Use host MOT_ROOT android-build/android-device/mot-computer owners and inspect their actual schema. No tool/provider fallback or global SDK/JDK changes.
No GitHub Actions, CI/CD, release automation or .github/workflows without explicit user override. Preserve unrelated edits.
