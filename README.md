# Kwikset 914 Zigbee Lock — Hubitat Driver

A Hubitat Elevation driver for the **Kwikset 914 Zigbee deadbolt** that fixes the battery reporting bug present in the built-in Generic Zigbee Lock driver, while implementing all of the same lock functionality.

---

## Why this driver exists

The Kwikset 914 (firmware `1092-000C-40A34412`, image type `0x000C` — 914TRL deadbolt) reports battery via **Zigbee cluster `0x0001`, attribute `0x0020` (BatteryVoltage)** in units of 100 mV. It does **not** implement attribute `0x0021` (BatteryPercentageRemaining) — requesting it returns `UNSUPPORTED_ATTRIBUTE`.

The Hubitat Generic Zigbee Lock driver primarily watches `0x0021`, and when it does encounter `0x0020` it treats the raw voltage count directly as a percentage — so a fully charged pack (raw value `60` = 6.0 V) reports as **60%** instead of **100%**, and the reading gets worse from there.

This driver:
- Configures reporting for `0x0020` only (no unsupported-attribute errors in the log)
- Converts the reported voltage to a percentage using a selectable battery chemistry curve
- Keeps a `0x0021` parse handler as a forward-compatible safety net for future firmware

---

## Features

| Feature | Details |
|---|---|
| Lock / Unlock | With "locking" / "unlocking" transitional states |
| Battery % | Derived from voltage via chemistry-specific curve |
| Battery voltage | Exposed as a separate `batteryVoltage` attribute (V) |
| Battery chemistry | Alkaline, Lithium, NiMH, or Custom voltage range |
| PIN code management | Full `Lock Codes` capability — set, delete, clear all |
| Keypad sync | Programming Event Notifications keep codes in sync when changed directly on the lock |
| Last user | `lastCodeName` attribute shows who last operated the lock |
| Lock jammed | Detected via Alarms cluster (0x0009) |
| Tamper alerts | Wrong code limit, escutcheon removed, forced open |

---

## Confirmed compatible firmware

| OTA version string | Image type | Notes |
|---|---|---|
| `1092-000C-40A34412` | `0x000C` | 914TRL deadbolt, Zigbee 3.0 — **primary target** |

Other 914 firmware versions that report battery via `0x0020` should also work. The driver includes fingerprints for `914TRL ZB3`, `SMARTCODE_DEADBOLT_914`, and `914TOUCHRF ZB3` model identifiers.

---

## Installation

### Option A — Import by URL (easiest)

1. In Hubitat, go to **Drivers Code → + New Driver → Import**
2. Paste this URL:
   ```
   https://raw.githubusercontent.com/iamnof1/hubitat-kwikset-914-zigbee/main/kwikset914-zigbee-lock.groovy
   ```
3. Click **Import**, then **Save**

### Option B — Manual

1. Open `kwikset914-zigbee-lock.groovy` from this repo
2. Copy the entire contents
3. In Hubitat, go to **Drivers Code → + New Driver**
4. Paste and click **Save**

### Assign the driver to your lock

1. In Hubitat, go to **Devices** and open your Kwikset 914
2. Change **Type** to `Kwikset 914 Zigbee Lock`
3. Click **Save Device**
4. Click **Configure** to push the corrected reporting settings to the lock

---

## Preferences

| Setting | Default | Description |
|---|---|---|
| Debug logging | On | Auto-disables after 30 minutes |
| Info text logging | On | Logs lock/unlock events to the hub log |
| Battery chemistry | Alkaline | Sets the voltage-to-% conversion curve (see below) |
| Custom min voltage | 3.5 V | Used only when chemistry is set to Custom |
| Custom max voltage | 6.0 V | Used only when chemistry is set to Custom |
| Max PIN slots | 30 | Upper bound for code slot numbers |

### Battery chemistry options

| Option | Voltage range | Notes |
|---|---|---|
| **Alkaline** *(default)* | 3.5 – 6.0 V | Best accuracy — gradual, roughly linear discharge curve |
| Lithium | 4.4 – 6.8 V | ⚠ Very flat curve — % reads high for most of battery life then drops sharply |
| NiMH rechargeable | 4.0 – 5.6 V | ⚠ Flat curve — similar limitation to lithium |
| Custom | User-defined | Enter your own min/max voltage thresholds |

> **Note:** The lock's `0x0020` attribute has 100 mV resolution, which limits accuracy to roughly ±4% on a 6 V pack regardless of chemistry setting.

---

## Changelog

### 2026-02-21
- **Fix:** `deleteCode()` now fires `codeChanged: "X deleted"` immediately on the hub's Clear PIN Code response, rather than waiting for a Programming Event Notification from the lock. Some firmware versions do not send Programming Events for hub-initiated deletes, which previously left Lock Code Manager with stale state. If a Programming Event does follow, it is handled harmlessly (idempotent remove).
- **Fix:** Lock events triggered by a keypad code now include `data: [usedCode: <slot>, codeName: "<name>"]` so that Lock Code Manager can attribute lock/unlock events to the named user who entered the code. Events from physical operation (thumb turn, key) carry an empty data map.
- **Fix:** Custom battery voltage thresholds where min ≥ max no longer cause an `ArithmeticException`. The driver now logs a warning and skips the battery event until valid thresholds are saved.

---

## License

GPL-3.0 — see [LICENSE](LICENSE)

This driver is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, version 3.

© 2026 Z Sachen
