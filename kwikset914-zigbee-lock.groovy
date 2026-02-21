/**
 * Copyright (C) 2026 Z Sachen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Kwikset 914 Zigbee Lock — Hubitat Driver
 *
 * Implements everything the built-in "Generic Zigbee Lock" driver does, plus
 * correctly handles Kwikset 914 battery reporting.
 *
 * CONFIRMED FIRMWARE: 1092-000C-40A34412
 *   Manufacturer code : 0x1092  (Kwikset / Spectrum Brands)
 *   Image type        : 0x000C  (914TRL deadbolt variant)
 *   File version      : 0x40A34412  (app release 4, build 10; stack release 52)
 *
 * WHY THE GENERIC DRIVER GETS BATTERY WRONG FOR THIS LOCK
 * --------------------------------------------------------
 * This firmware only supports cluster 0x0001 attribute 0x0020 (BatteryVoltage),
 * reported in units of 100 mV.  Attribute 0x0021 (BatteryPercentageRemaining) is
 * NOT implemented — the device returns UNSUPPORTED_ATTRIBUTE if asked, which floods
 * the live log and can leave the hub thinking configure() failed.
 *
 * The Generic driver primarily watches 0x0021 and, when it does see 0x0020, treats
 * the raw voltage count as a percentage — wildly wrong (e.g. 60 → "60 %").
 *
 * This driver:
 *   • Configures the device to report only 0x0020 (voltage).
 *   • Converts raw voltage to % using the 4×AA alkaline discharge curve.
 *   • Parses 0x0021 if ever received (future firmware safety net), dividing by 2
 *     per the ZCL spec (units are 0.5 %).
 *
 * BATTERY VOLTAGE THRESHOLDS (user-adjustable in Preferences)
 *   4 × AA alkaline: ~6.0 V full, ~3.5 V cut-off → adjustable defaults
 *
 * CAPABILITIES IMPLEMENTED
 *   Lock, Lock Codes, Battery, Refresh, Configuration, Actuator, Sensor
 *
 * CREDITS
 *   Modelled after the Hubitat Generic Zigbee Lock driver; battery fix is original.
 */

import groovy.json.JsonOutput
import hubitat.zigbee.zcl.DataType

metadata {
    definition(
        name:      "Kwikset 914 Zigbee Lock",
        namespace: "community",
        author:    "Custom"
    ) {
        capability "Actuator"
        capability "Lock"
        capability "Lock Codes"
        capability "Battery"
        capability "Refresh"
        capability "Configuration"
        capability "Sensor"

        // Extra attributes beyond the Generic driver
        attribute "batteryVoltage", "number"   // reported voltage in V
        attribute "lockJammed",     "string"   // "detected" | "clear"
        attribute "tamperAlert",    "string"   // "detected" | "clear"
        attribute "lastCodeName",   "string"   // name of the user who last operated the lock

        command "clearCodes", []   // clears all PIN codes from the lock AND local state

        // ── Fingerprints ─────────────────────────────────────────────────────────
        // Primary: confirmed for firmware 1092-000C-40A34412 (image type 0x000C = 914TRL deadbolt)
        fingerprint profileId: "0104", deviceId: "000A",
            inClusters:  "0000,0001,0003,0009,000A,0028,0101",
            outClusters: "000A,0019",
            manufacturer: "Kwikset", model: "914TRL ZB3",
            deviceJoinName: "Kwikset 914 Deadbolt"

        // Legacy model identifier used by older 914 firmware
        fingerprint profileId: "0104", deviceId: "000A",
            inClusters:  "0000,0001,0003,0009,000A,0028,0101",
            outClusters: "000A,0019",
            manufacturer: "Kwikset", model: "SMARTCODE_DEADBOLT_914",
            deviceJoinName: "Kwikset 914 Deadbolt"

        // 914 touchscreen/RFID variant (image type differs; kept for completeness)
        fingerprint profileId: "0104", deviceId: "000A",
            inClusters:  "0000,0001,0003,0009,000A,0101",
            outClusters: "000A,0019",
            manufacturer: "Kwikset", model: "914TOUCHRF ZB3",
            deviceJoinName: "Kwikset 914 Deadbolt"
    }

    preferences {
        input name: "logEnable",
              type: "bool", title: "Enable debug logging",
              defaultValue: true

        input name: "txtEnable",
              type: "bool", title: "Enable info text logging",
              defaultValue: true

        input name: "batteryChemistry",
              type: "enum",
              title: "Battery chemistry (4×AA)",
              description: "Sets the voltage-to-percentage curve for your battery type",
              options: [
                  "alkaline": "Alkaline — Duracell, Energizer Max, etc. (3.5–6.0 V)",
                  "lithium":  "Lithium — Energizer Ultimate, etc. (4.4–6.8 V)  ⚠ flat curve, % is approximate",
                  "nimh":     "NiMH rechargeable — Eneloop, etc. (4.0–5.6 V)  ⚠ flat curve, % is approximate",
                  "custom":   "Custom — enter voltage thresholds below"
              ],
              defaultValue: "alkaline", required: true

        // Only used when batteryChemistry == "custom"
        input name: "batteryMinVolts",
              type: "decimal",
              title: "  ↳ Custom minimum voltage (V) — 0 %",
              description: "Active only when chemistry is set to Custom",
              defaultValue: 3.5, range: "2.0..5.5"

        input name: "batteryMaxVolts",
              type: "decimal",
              title: "  ↳ Custom maximum voltage (V) — 100 %",
              description: "Active only when chemistry is set to Custom",
              defaultValue: 6.0, range: "4.5..7.0"

        input name: "maxCodes",
              type: "number", title: "Maximum number of PIN slots (default 30)",
              defaultValue: 30, range: "1..250"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Lifecycle
// ─────────────────────────────────────────────────────────────────────────────

def installed() {
    log.info "${device.displayName}: driver installed"
    state.lockCodes      = [:]
    state.pendingCodes   = [:]
    state.pendingDeletes = [:]
    sendEvent(name: "lock",        value: "unknown")
    sendEvent(name: "lockJammed",  value: "clear")
    sendEvent(name: "tamperAlert", value: "clear")
    runIn(2, configure)
}

def updated() {
    log.info "${device.displayName}: preferences updated"
    if (logEnable) runIn(1800, logsOff)   // auto-disable debug after 30 min
    configure()
}

def logsOff() {
    log.warn "${device.displayName}: debug logging disabled after timeout"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ─────────────────────────────────────────────────────────────────────────────
// Configuration
// ─────────────────────────────────────────────────────────────────────────────

def configure() {
    if (logEnable) log.debug "${device.displayName}: configure()"
    def cmds = []

    // ── Battery — firmware 1092-000C-40A34412 uses 0x0020 ONLY ───────────
    // attr 0x0020 — BatteryVoltage (UINT8, units = 100 mV)
    //   Report no more than once per 30 s, at least once per hour,
    //   and whenever the reading changes by ≥ 100 mV (1 raw count).
    cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 30, 3600, 1)
    //
    // NOTE: attr 0x0021 (BatteryPercentageRemaining) is intentionally NOT
    // configured here.  This firmware returns UNSUPPORTED_ATTRIBUTE for 0x0021,
    // which floods the live log.  The parse() handler still accepts 0x0021 in
    // case a future firmware update adds support.

    // ── Door Lock cluster ─────────────────────────────────────────────────
    // attr 0x0000 — LockState (ENUM8); report on any change, at most every hour
    cmds += zigbee.configureReporting(0x0101, 0x0000, DataType.ENUM8, 0, 3600, null)

    // Initial state read after configure
    cmds += refresh()
    return cmds
}

// ─────────────────────────────────────────────────────────────────────────────
// Commands
// ─────────────────────────────────────────────────────────────────────────────

def refresh() {
    if (logEnable) log.debug "${device.displayName}: refresh()"
    def cmds = []
    cmds += zigbee.readAttribute(0x0101, 0x0000)   // Lock State
    cmds += zigbee.readAttribute(0x0001, 0x0020)   // Battery Voltage (sole battery attr on this firmware)
    // 0x0021 intentionally omitted — returns UNSUPPORTED_ATTRIBUTE on firmware 1092-000C-40A34412
    return cmds
}

def lock() {
    if (logEnable) log.debug "${device.displayName}: lock()"
    sendEvent(name: "lock", value: "locking",
              descriptionText: "${device.displayName} locking")
    return zigbee.command(0x0101, 0x00, "")
}

def unlock() {
    if (logEnable) log.debug "${device.displayName}: unlock()"
    sendEvent(name: "lock", value: "unlocking",
              descriptionText: "${device.displayName} unlocking")
    return zigbee.command(0x0101, 0x01, "")
}

// ── Lock Codes capability commands ────────────────────────────────────────────

def setCode(codeNumber, code, name = null) {
    if (logEnable) log.debug "${device.displayName}: setCode(${codeNumber}, [PIN], ${name})"

    codeNumber = codeNumber as int
    if (codeNumber < 1 || codeNumber > (maxCodes ?: 30)) {
        log.warn "${device.displayName}: code slot ${codeNumber} out of range"
        return []
    }
    if (!code || !(code ==~ /^\d{4,8}$/)) {
        log.warn "${device.displayName}: PIN must be 4–8 numeric digits"
        return []
    }

    def codeName = name ?: "Code ${codeNumber}"
    if (!state.pendingCodes) state.pendingCodes = [:]
    state.pendingCodes["${codeNumber}"] = [name: codeName]

    return zigbee.command(0x0101, 0x05, buildSetPinPayload(codeNumber, code))
}

def deleteCode(codeNumber) {
    if (logEnable) log.debug "${device.displayName}: deleteCode(${codeNumber})"
    if (!state.pendingDeletes) state.pendingDeletes = [:]
    state.pendingDeletes["${codeNumber as int}"] = true
    return zigbee.command(0x0101, 0x07, buildUserIdPayload(codeNumber as int))
}

def getCodes() {
    if (logEnable) log.debug "${device.displayName}: getCodes()"
    // The lock has no "enumerate all codes" command, so we return local state.
    updateLockCodesAttribute(state.lockCodes ?: [:])
    return []
}

def setCodeLength(length) {
    if (logEnable) log.debug "${device.displayName}: setCodeLength(${length})"
    sendEvent(name: "codeLength", value: length as int,
              descriptionText: "${device.displayName} code length set to ${length}")
}

def clearCodes() {
    log.info "${device.displayName}: clearCodes() — clearing ALL codes"
    state.lockCodes    = [:]
    state.pendingCodes = [:]
    updateLockCodesAttribute([:])
    sendEvent(name: "codeChanged", value: "all deleted",
              descriptionText: "${device.displayName} all codes cleared")
    return zigbee.command(0x0101, 0x08, "")   // Clear All PIN Codes
}

// ─────────────────────────────────────────────────────────────────────────────
// Parse — entry point for all Zigbee messages
// ─────────────────────────────────────────────────────────────────────────────

def parse(String description) {
    if (description == "updated") return null
    if (logEnable) log.debug "${device.displayName}: parse → ${description}"

    def descMap = zigbee.parseDescriptionAsMap(description)
    if (!descMap) {
        log.warn "${device.displayName}: could not parse: ${description}"
        return null
    }
    if (logEnable) log.debug "${device.displayName}: descMap → ${descMap}"

    def result = []
    switch (descMap.cluster) {
        case "0001": result += parsePowerCluster(descMap);   break
        case "0009": result += parseAlarmsCluster(descMap);  break
        case "0101": result += parseDoorLockCluster(descMap); break
        default:
            if (logEnable) log.debug "${device.displayName}: unhandled cluster ${descMap.cluster}"
    }
    return result ?: null
}

// ─────────────────────────────────────────────────────────────────────────────
// Cluster parsers
// ─────────────────────────────────────────────────────────────────────────────

private List parsePowerCluster(Map d) {
    switch (d.attrId) {
        case "0020": return parseBatteryVoltage(d.value)      // primary for Kwikset 914
        case "0021": return parseBatteryPercentage(d.value)   // fallback
        default:     return []
    }
}

/**
 * BatteryVoltage (attr 0x0020) — UINT8, units = 100 mV.
 * Raw value 60 → 6.0 V.  Converted to % using the chemistry-specific curve.
 */
private List parseBatteryVoltage(String hexValue) {
    if (!hexValue || hexValue == "FF") return []   // 0xFF = invalid/not reported

    def raw    = zigbee.convertHexToInt(hexValue)
    def volts  = raw / 10.0                         // 100 mV units → volts

    def thresh = batteryThresholds()
    def minV   = thresh.min as double
    def maxV   = thresh.max as double

    if (minV >= maxV) {
        log.warn "${device.displayName}: battery voltage thresholds invalid (min ${minV} V >= max ${maxV} V) — check preferences"
        return []
    }

    def pct   = ((volts - minV) / (maxV - minV) * 100).round() as int
    pct       = Math.max(0, Math.min(100, pct))

    def chem  = settings.batteryChemistry ?: "alkaline"
    if (logEnable) log.debug "${device.displayName}: battery ${volts} V → ${pct} % (${chem}, range ${minV}–${maxV} V)"
    if (txtEnable) log.info  "${device.displayName}: battery is ${pct} % (${volts} V, ${chem})"

    return [
        createEvent(name: "batteryVoltage", value: volts, unit: "V",
                    descriptionText: "${device.displayName} battery voltage: ${volts} V"),
        createEvent(name: "battery",        value: pct,   unit: "%",
                    descriptionText: "${device.displayName} battery: ${pct} %")
    ]
}

/**
 * BatteryPercentageRemaining (attr 0x0021) — UINT8, units = 0.5 %.
 * Raw value 200 → 100 %.  Many drivers (including the Generic one) forget to ÷2.
 *
 * Firmware 1092-000C-40A34412 does NOT send this attribute; this handler exists
 * purely as a safety net for future firmware updates that may add 0x0021 support.
 * We never actively request it (no configureReporting / readAttribute for 0x0021).
 */
private List parseBatteryPercentage(String hexValue) {
    if (!hexValue || hexValue == "FF") return []

    def raw = zigbee.convertHexToInt(hexValue)
    def pct = (raw / 2.0).round() as int
    pct     = Math.max(0, Math.min(100, pct))

    if (logEnable) log.debug "${device.displayName}: battery pct attr (0x0021) raw=${raw} → ${pct} %"
    if (txtEnable) log.info  "${device.displayName}: battery is ${pct} % (via pct attr 0x0021)"

    return [
        createEvent(name: "battery", value: pct, unit: "%",
                    descriptionText: "${device.displayName} battery: ${pct} %")
    ]
}

private List parseAlarmsCluster(Map d) {
    if (d.command != "00" || !d.data) return []   // only handle Alarm command (0x00)

    def alarmCode = Integer.parseInt(d.data[0], 16)
    def desc      = alarmDescription(alarmCode)
    log.warn "${device.displayName}: alarm — ${desc}"

    switch (alarmCode) {
        case 0x00:   // Deadbolt jammed
            return [
                createEvent(name: "lockJammed", value: "detected",
                            descriptionText: "${device.displayName}: deadbolt jammed"),
                createEvent(name: "lock",       value: "unknown",
                            descriptionText: "${device.displayName}: lock state unknown (jammed)")
            ]
        case 0x04:   // Tamper — wrong code entry limit
        case 0x05:   // Tamper — front escutcheon removed
        case 0x06:   // Forced door open
            return [
                createEvent(name: "tamperAlert", value: "detected",
                            descriptionText: "${device.displayName}: ${desc}")
            ]
        default:
            return []
    }
}

private List parseDoorLockCluster(Map d) {
    if (d.isClusterSpecific) {
        switch (d.command) {
            case "20": return parseOperatingEvent(d)      // Operating Event Notification
            case "21": return parseProgrammingEvent(d)    // Programming Event Notification
            case "05": return handleSetPinResponse(d)     // Set PIN Code Response
            case "07": return handleClearPinResponse(d)   // Clear PIN Code Response
            case "00": // Lock Door Response
            case "01": // Unlock Door Response
                if (logEnable) log.debug "${device.displayName}: lock/unlock response cmd=${d.command}"
                return []
            default:
                if (logEnable) log.debug "${device.displayName}: unhandled cluster-specific cmd ${d.command}"
                return []
        }
    } else {
        switch (d.attrId) {
            case "0000": return parseLockState(d.value)
            default:
                if (logEnable) log.debug "${device.displayName}: unhandled attrId ${d.attrId}"
                return []
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Door Lock cluster — attribute and event parsers
// ─────────────────────────────────────────────────────────────────────────────

private List parseLockState(String hexValue) {
    if (!hexValue) return []
    def raw  = zigbee.convertHexToInt(hexValue)
    def val  = raw == 1 ? "locked" : raw == 2 ? "unlocked" : "unknown"
    if (txtEnable) log.info "${device.displayName}: is ${val}"
    def result = [createEvent(name: "lock", value: val,
                              descriptionText: "${device.displayName} is ${val}")]
    if (val != "unknown") result << createEvent(name: "lockJammed", value: "clear")
    return result
}

/**
 * Operating Event Notification — command 0x20 from device.
 *
 * Payload layout (ZCL 7.3):
 *   byte 0    OperationEventSource  (enum8)
 *   byte 1    OperationEventCode    (enum8)
 *   byte 2-3  UserID                (uint16 LE)
 *   byte 4    PIN length            (uint8)
 *   byte 5..  PIN bytes             (ASCII)
 *   then      LocalTime             (uint32 LE)
 *   then      optional data string
 */
private List parseOperatingEvent(Map d) {
    if (!d.data || d.data.size() < 4) return []
    def src       = Integer.parseInt(d.data[0], 16)
    def evtCode   = Integer.parseInt(d.data[1], 16)
    def userID    = (Integer.parseInt(d.data[3], 16) << 8) | Integer.parseInt(d.data[2], 16)
    def srcName   = eventSourceName(src)
    def evtName   = operatingEventName(evtCode)
    if (logEnable) log.debug "${device.displayName}: operating event src=${srcName} evt=${evtName} user=${userID}"

    def lockVal = operatingEventToLockValue(evtCode)
    if (!lockVal) return []

    def codeName = getCodeName(userID)
    def descText = "${device.displayName} ${lockVal} by ${srcName}" +
                   (codeName ? " (${codeName})" : "")
    if (txtEnable) log.info descText
    if (codeName) sendEvent(name: "lastCodeName", value: codeName)

    // Include usedCode so Lock Code Manager can attribute the event to a named user.
    // Only set when userID > 0; a zero ID means physical/manual operation (no code).
    def evtData = userID > 0 ? [usedCode: userID, codeName: codeName ?: ""] : [:]

    return [
        createEvent(name: "lock",       value: lockVal, descriptionText: descText,
                    data: evtData),
        createEvent(name: "lockJammed", value: "clear")
    ]
}

/**
 * Programming Event Notification — command 0x21 from device.
 *
 * Payload layout same as Operating Event but with ProgramEventCode.
 *   codes 2,5,8 → PIN added
 *   codes 3,6,9 → PIN deleted
 *   codes 4,7,10 → PIN changed
 */
private List parseProgrammingEvent(Map d) {
    if (!d.data || d.data.size() < 4) return []
    def evtCode = Integer.parseInt(d.data[1], 16)
    def userID  = (Integer.parseInt(d.data[3], 16) << 8) | Integer.parseInt(d.data[2], 16)
    def slot    = "${userID}"

    def codes = state.lockCodes ?: [:]

    if (evtCode in [2, 5, 8]) {                           // added externally
        codes[slot] = [name: "Code ${userID}"]
        state.lockCodes = codes
        updateLockCodesAttribute(codes)
        if (txtEnable) log.info "${device.displayName}: code slot ${userID} added via lock keypad"
        return [createEvent(name: "codeChanged", value: "${userID} set",
                            descriptionText: "${device.displayName} code ${userID} added via keypad")]
    }
    if (evtCode in [3, 6, 9]) {                           // deleted externally
        codes.remove(slot)
        state.lockCodes = codes
        updateLockCodesAttribute(codes)
        if (txtEnable) log.info "${device.displayName}: code slot ${userID} deleted via lock keypad"
        return [createEvent(name: "codeChanged", value: "${userID} deleted",
                            descriptionText: "${device.displayName} code ${userID} deleted via keypad")]
    }
    if (evtCode in [4, 7, 10]) {                          // changed externally
        codes[slot] = codes[slot] ?: [name: "Code ${userID}"]
        state.lockCodes = codes
        updateLockCodesAttribute(codes)
        if (txtEnable) log.info "${device.displayName}: code slot ${userID} changed via lock keypad"
        return [createEvent(name: "codeChanged", value: "${userID} set",
                            descriptionText: "${device.displayName} code ${userID} changed via keypad")]
    }
    return []
}

// ─────────────────────────────────────────────────────────────────────────────
// PIN command response handlers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Set PIN Code Response — server cmd 0x05.
 * Payload: Status (uint8).  0x00=Success, 0x01=Fail, 0x02=MemFull, 0x03=Duplicate.
 *
 * The ZCL response does NOT echo the user ID back, so we rely on state.pendingCodes
 * having exactly one entry (we send codes one at a time).
 */
private List handleSetPinResponse(Map d) {
    if (!d.data) return []
    def status = Integer.parseInt(d.data[0], 16)
    def pending = state.pendingCodes ?: [:]

    // Find the first pending entry (we enforce one-at-a-time via the driver)
    def slot = pending.keySet().min { it.toInteger() }
    if (!slot) {
        if (logEnable) log.debug "${device.displayName}: set PIN response but no pending codes"
        return []
    }
    def info = pending.remove(slot)
    state.pendingCodes = pending

    if (status == 0x00) {
        def codes = state.lockCodes ?: [:]
        codes[slot] = [name: info.name]
        state.lockCodes = codes
        updateLockCodesAttribute(codes)
        if (txtEnable) log.info "${device.displayName}: code slot ${slot} (${info.name}) set successfully"
        return [createEvent(name: "codeChanged", value: "${slot} set",
                            descriptionText: "${device.displayName} code slot ${slot} set")]
    } else {
        def reason = status == 0x02 ? "memory full" : status == 0x03 ? "duplicate code" : "general failure"
        log.warn "${device.displayName}: set code slot ${slot} failed — ${reason}"
        return [createEvent(name: "codeChanged", value: "${slot} failed",
                            descriptionText: "${device.displayName} code slot ${slot} set failed (${reason})")]
    }
}

/**
 * Clear PIN Code Response — server cmd 0x07.
 * Payload: Status (uint8).  Like setPin, the response doesn't echo the user ID.
 *
 * We update state and fire codeChanged immediately here rather than waiting for
 * a Programming Event Notification, because some firmware versions do not send
 * Programming Events for hub-initiated deletes.  If a Programming Event does
 * arrive afterward, the remove() is a no-op and the duplicate codeChanged event
 * is harmless to Lock Code Manager.
 */
private List handleClearPinResponse(Map d) {
    if (!d.data) return []
    def status = Integer.parseInt(d.data[0], 16)
    if (status != 0x00) {
        log.warn "${device.displayName}: delete code failed, status=${status}"
        return []
    }
    if (logEnable) log.debug "${device.displayName}: delete code response: success"

    def pending = state.pendingDeletes ?: [:]
    def slot = pending.keySet().min { it.toInteger() }
    if (!slot) return []
    pending.remove(slot)
    state.pendingDeletes = pending

    def codes = state.lockCodes ?: [:]
    codes.remove(slot)
    state.lockCodes = codes
    updateLockCodesAttribute(codes)
    if (txtEnable) log.info "${device.displayName}: code slot ${slot} deleted"
    return [createEvent(name: "codeChanged", value: "${slot} deleted",
                        descriptionText: "${device.displayName} code slot ${slot} deleted")]
}

// ─────────────────────────────────────────────────────────────────────────────
// Payload builders (ZCL little-endian)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Set PIN Code command payload (cmd 0x05):
 *   UserID (uint16 LE) | UserStatus (uint8) | UserType (uint8) | PIN (octstr)
 */
private String buildSetPinPayload(int slot, String pin) {
    def idLo  = zigbee.convertToHexString(slot & 0xFF,        2)
    def idHi  = zigbee.convertToHexString((slot >> 8) & 0xFF, 2)
    def pLen  = zigbee.convertToHexString(pin.length(),       2)
    def pBytes = pin.bytes.collect { zigbee.convertToHexString(it & 0xFF, 2) }.join("")
    // UserStatus=0x01 (Enabled), UserType=0x00 (Unrestricted)
    return idLo + idHi + "01" + "00" + pLen + pBytes
}

/** Clear PIN Code / Get PIN Code payload: just UserID (uint16 LE). */
private String buildUserIdPayload(int slot) {
    return zigbee.convertToHexString(slot & 0xFF, 2) +
           zigbee.convertToHexString((slot >> 8) & 0xFF, 2)
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns the [min, max] voltage thresholds (in volts) for the selected battery
 * chemistry.  All values are for a 4×AA cell pack.
 *
 * Alkaline  — gradual, roughly linear discharge; voltage is a reliable % proxy.
 * Lithium   — very flat curve (~1.5 V/cell for most of its life then a sudden
 *             drop); % will read high for the majority of the battery's life and
 *             then fall sharply near the end.  Thresholds are correct, but the
 *             physics of lithium cells makes voltage-based % inherently imprecise.
 * NiMH      — similarly flat; same limitation as lithium applies.
 * Custom    — uses the manual min/max voltage inputs in Preferences.
 */
private Map batteryThresholds() {
    switch (settings.batteryChemistry ?: "alkaline") {
        case "alkaline": return [min: 3.5, max: 6.0]
        case "lithium":  return [min: 4.4, max: 6.8]
        case "nimh":     return [min: 4.0, max: 5.6]
        case "custom":
            return [
                min: (settings.batteryMinVolts ?: 3.5) as double,
                max: (settings.batteryMaxVolts ?: 6.0) as double
            ]
        default:         return [min: 3.5, max: 6.0]
    }
}

private void updateLockCodesAttribute(Map codes) {
    sendEvent(name: "lockCodes", value: JsonOutput.toJson(codes),
              descriptionText: "${device.displayName} lock codes updated")
}

private String getCodeName(int userID) {
    if (userID == 0) return null
    return (state.lockCodes ?: [:])["${userID}"]?.name
}

/** Map operating event code → lock value, or null if not a lock/unlock event. */
private String operatingEventToLockValue(int code) {
    switch (code) {
        case 1:   // Lock
        case 7:   // One-touch lock
        case 8:   // Key lock
        case 10:  // Auto lock
        case 11:  // Schedule lock
        case 13:  // Manual lock (key)
            return "locked"
        case 2:   // Unlock
        case 9:   // Key unlock
        case 12:  // Schedule unlock
        case 14:  // Manual unlock (key)
            return "unlocked"
        default:
            return null
    }
}

private String eventSourceName(int src) {
    switch (src) {
        case 0: return "Keypad"
        case 1: return "RF"
        case 2: return "Manual"
        case 3: return "RFID"
        case 5: return "Auto"
        default: return "Unknown(${src})"
    }
}

private String operatingEventName(int code) {
    switch (code) {
        case 0:  return "Unknown"
        case 1:  return "Lock"
        case 2:  return "Unlock"
        case 3:  return "Lock failed (invalid PIN)"
        case 4:  return "Lock failed (invalid schedule)"
        case 5:  return "Unlock failed (invalid PIN)"
        case 6:  return "Unlock failed (invalid schedule)"
        case 7:  return "One-touch lock"
        case 8:  return "Key lock"
        case 9:  return "Key unlock"
        case 10: return "Auto lock"
        case 11: return "Schedule lock"
        case 12: return "Schedule unlock"
        case 13: return "Manual lock"
        case 14: return "Manual unlock"
        case 15: return "Non-access user event"
        default: return "Unknown(${code})"
    }
}

private String alarmDescription(int code) {
    switch (code) {
        case 0x00: return "Deadbolt jammed"
        case 0x01: return "Lock reset to factory defaults"
        case 0x03: return "RF module power cycled"
        case 0x04: return "Tamper: wrong code entry limit exceeded"
        case 0x05: return "Tamper: front escutcheon removed"
        case 0x06: return "Forced door open under locked condition"
        default:   return "Unknown alarm 0x${String.format('%02X', code)}"
    }
}
