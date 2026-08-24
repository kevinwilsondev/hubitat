/**
 *  Sonoff Zigbee Metering Mini Dimmer (MINI-ZBDIM) driver for Hubitat Elevation
 *  
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *
 *  This is a Hubitat Elevation device driver for the Sonoff MINI-ZBDIM module. It supports power metering, external
 *  switch mode, power-on behavior, and fade durations for both Zigbee commands and from the external switch.
 *
 *  An explanation of some of the preferences and commands:
 *
 *  - Standby ghost power:
 *    The device seems to always report a small power draw even when the output is switched off. It might be measuring
 *    its own power draw, or it might just be inaccurate. There are two preferences to stop this:
 *
 *    1. "Standby ghost power": this is the power, in Watts, that will be subtracted from the reported power sent by the
 *                              device. Default is 0.07.
 *    2. "Standby power threshold": any power report sent by the device that's less than this amount (in Watts) will be
 *                                  set to zero, so it appears fully off. Default is 0.05, 0.0 disables it.
 *
 *  - Fade transition times:
 *    The device seems to ignore the fade duration when setting the brightness level. It does, however, have a setting
 *    for a global fade duration. By sending the command to set this before the brightness/on/off command we can
 *    achieve the same effect. This is DISABLED BY DEFAULT because every time the duration is changed: 
 *
 *    1. The device will write the setting to its NVRAM, which could wear it out quite quickly.
 *    2. There will be a slight delay while the command is sent before the lights change.
 *
 *    The duration command is only sent if it's different from the last duration used, so if you rarely set a duration
 *    when you change brightness, it's probably safe to enable this with the "Allow dynamic transition speeds" setting.
 *    The "Default transition speed" setting is the duration for brightness changes with no duration included, and for 
 *    switching on and off. The default is 2.5 seconds.
 *
 *    Unlike some other dimmers, this device treats the duration as how long it should take if changing from 0% to 100% 
 *    (or 100% to 0%). If you change from 100% to 50%, for example, it will take half that amount of time.
 *
 *    There's a separate setting, "External switch fade rate", to control how quickly the brightness changes when using
 *    the external switch. It's a multiplier that speeds it up or slows it down, rather than a specific duration.
 *
 *  - Calibration:
 *    The device can run a calibration to detect the properties of the bulbs connected to it. To trigger this use the
 *    "Start Calibration" command. After a few seconds the calibration will start and, if you have descriptionText 
 *    logging enabled, you'll start to see the calibration progress percentage appear in the logs. The calibration
 *    process might take one or two minutes.
 *
 */

import groovy.transform.Field

@Field static final String VERSION = "1.0.0"
@Field static final String SONOFF_MFG_ID = "0x128C"
@Field static final String SONOFF_CLUSTER_ID = "FC11"
@Field static final Integer SONOFF_CLUSTER_ID_HEX = 0xFC11
@Field static final Integer SONOFF_CLUSTER_INT = 64529
@Field static final String POWER_ON_BEHAVIOR_PREVIOUS = "Previous State"
@Field static final String POWER_ON_BEHAVIOR_OFF = "Off"
@Field static final String POWER_ON_BEHAVIOR_ON = "On"
@Field static final String POWER_ON_BEHAVIOR_TOGGLE = "Toggle Previous"
@Field static final String SWITCH_MODE_TRIPLE = "Triple Button"
@Field static final String SWITCH_MODE_DUAL = "Dual Button"
@Field static final String SWITCH_MODE_MOMENTARY = "Single Momentary"
@Field static final String SWITCH_MODE_TOGGLE = "Single Toggle"
@Field static final Integer DEFAULT_SWITCH_RATE = 4
@Field static final Float DEFAULT_TRANSITION_RATE = 2.5
@Field static final Float DEFAULT_STANDBY_GHOST_POWER = 0.07
@Field static final Float DEFAULT_STANDBY_THRESHOLD = 0.05


metadata {
    definition (name: "Sonoff Zigbee Metering Mini Dimmer", namespace: "kevinwilsondev.hubitat", author: "Kevin Wilson", importUrl: "https://raw.githubusercontent.com/kevinwilsondev/hubitat/main/devices/Sonoff%20MINI-ZBDIM/Sonoff%20Zigbee%20Metering%20Mini%20Dimmer.groovy") {
        capability "Switch"
        capability "SwitchLevel"
        capability "PowerMeter"
        capability "VoltageMeasurement"
        capability "CurrentMeter"
        capability "Configuration"
        capability "Refresh"

        command "startCalibration"
        command "stopCalibration"

        attribute "calibrationStatus", "string"
        attribute "calibrationProgress", "number"
        attribute "powerOnBehavior", "string"
        attribute "switchMode", "string"
        attribute "switchRate", "number"

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,0008,FC11", outClusters: "0019", model: "MINI-ZBDIM", manufacturer: "SONOFF"
    }

    preferences {
        input name: "switchMode", type: "enum", title: "External switch type", defaultValue: SWITCH_MODE_TRIPLE, options: [SWITCH_MODE_TRIPLE, SWITCH_MODE_DUAL, SWITCH_MODE_MOMENTARY, SWITCH_MODE_TOGGLE]
        input name: "switchRate", type: "number", title: "External switch fade rate", defaultValue: DEFAULT_SWITCH_RATE, range: "1..5"
        input name: "powerOnBehavior", type: "enum", title: "Power-on behavior", defaultValue: POWER_ON_BEHAVIOR_PREVIOUS, options: [POWER_ON_BEHAVIOR_PREVIOUS, POWER_ON_BEHAVIOR_OFF, POWER_ON_BEHAVIOR_ON, POWER_ON_BEHAVIOR_TOGGLE]
        input name: "defaultTransition", type: "number", title: "Default transition speed (seconds)", defaultValue: DEFAULT_TRANSITION_RATE, range: "0..60"
        input name: "dynamicTransitions", type: "bool", title: "Allow dynamic transition speeds (not recommended)", description: "<small>Increases NVRAM wear: read README before enabling</small>", defaultValue: false
        input name: "standbyGhostPower", type: "number", title: "Standby ghost power (Watts)", defaultValue: DEFAULT_STANDBY_GHOST_POWER, range: "0..5"
        input name: "standbyPowerThreshold", type: "number", title: "Standby power threshold (Watts)", defaultValue: DEFAULT_STANDBY_THRESHOLD, range: "0..5"
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}


// Parse incoming Zigbee messages
def parse(String description) {
    logDebug("Parse description: ${description}")

    // Check if this is the custom Sonoff cluster
    if (description?.contains("cluster: ${SONOFF_CLUSTER_ID}") || description?.contains("clusterInt: ${SONOFF_CLUSTER_INT}")) {
        parseCustomSonoffCluster(description)
        return
    }

    // Attempt standard Zigbee extraction patterns
    def map = zigbee.getEvent(description)
    if (map) {
        if (map.name == "switch") {
            logText("${device.displayName} is ${map.value}")
            sendEvent(map)
        }
        else if (map.name == "level") {
            handleLevelReporting(map.value.toInteger())
        }
    }
    else {
        def descMap = zigbee.parseDescriptionAsMap(description)
        logDebug("Parsed map: ${descMap}")

        if ((descMap.clusterInt == SONOFF_CLUSTER_INT || descMap.clusterId == SONOFF_CLUSTER_ID) && description.startsWith("catchall:")) {
            logDebug("Sonoff catchall")
            return
        }

        // Power-on behavior (genOnOff cluster, standard attribute 0x4003 "startUpOnOff") - arrives either as an attribute report (command 0A) or a read attributes
        // response (command 01) depending on whether it was pushed or explicitly read via refresh()/configure().
        if (descMap.clusterInt == 6 && descMap.attrId == "4003") {
            String behaviorText = ["00": POWER_ON_BEHAVIOR_OFF, "01": POWER_ON_BEHAVIOR_ON, "02": POWER_ON_BEHAVIOR_TOGGLE, "FF": POWER_ON_BEHAVIOR_PREVIOUS][descMap.value?.toUpperCase()] ?: "unknown (${descMap.value})"
            logText("${device.displayName} power-on behavior is ${behaviorText}")
            sendEvent(name: "powerOnBehavior", value: behaviorText)
        }
        // On/Off reporting
        else if (descMap.clusterInt == 6 && descMap.command == "0A") {
            def value = descMap.value == "01" ? "on" : "off"
            logText("${device.displayName} is ${value}")
            sendEvent(name: "switch", value: value)
        }
        // Level reporting
        else if (descMap.clusterInt == 8 && descMap.command == "0A") {
            int rawLevel = Integer.parseInt(descMap.value, 16)
            int level = Math.round(rawLevel * 100 / 254)
            handleLevelReporting(level)
        }
    }
}

private void handleLevelReporting(int level) {
    if (level > 0) {
        state.lastLevel = level  // Cache the active level value
        logText("${device.displayName} level is ${level}%")
        sendEvent(name: "level", value: level, unit: "%")
    }
    else {
        // Device reports 0% because it turned off. Do not update 'level' event attribute.
        logDebug("Intercepted 0% level report. Preserving level at ${state.lastLevel ?: 100}%")
    }
}

private void parseCustomSonoffCluster(String description) {
    def map = zigbee.parseDescriptionAsMap(description)
    logDebug("Parsing Sonoff cluster: ${map}")

    def attributes = []
    if (map.attrId) {
        attributes << [id: map.attrId, val: map.value]
    }
    if (map.additionalAttrs) {
        map.additionalAttrs.each { attributes << [id: it.attrId, val: it.value] }
    }

    double currentVoltage = device.currentValue("voltage") ?: 235.0
    double currentPower = device.currentValue("power") ?: 0.0
    float ghostPower = standbyGhostPower ? standbyGhostPower.toFloat() : DEFAULT_STANDBY_GHOST_POWER
    float powerThreshold = standbyPowerThreshold ? standbyPowerThreshold.toFloat() : DEFAULT_STANDBY_THRESHOLD
    boolean powerUpdated = false
    boolean voltageUpdated = false

    attributes.each { attr ->
        try {
            long rawValue = Long.parseLong(attr.val, 16)

            logDebug("Sonoff cluster, ${attr.id?.toUpperCase()}=${rawValue} (${attr.val})")

            switch (attr.id?.toUpperCase()) {
                // External switch mode
                case "0016":
                    String statusText = ["0": SWITCH_MODE_TOGGLE, "1": SWITCH_MODE_MOMENTARY, "3": SWITCH_MODE_DUAL, "4": SWITCH_MODE_TRIPLE]["${rawValue}"] ?: "unknown (${rawValue})"
                    logText("${device.displayName} external switch mode is ${statusText}")
                    sendEvent(name: "switchMode", value: statusText)
                    break

                // External switch rate
                case "4003":
                    logText("${device.displayName} external switch rate is ${rawValue}")
                    sendEvent(name: "switchRate", value: rawValue)
                    break

                // Calibration status (0 = uncalibrated, 1 = calibrating, 2 = calibration_failed, 3 = calibrated)
                case "001E":
                    String statusText = ["0": "uncalibrated", "1": "calibrating", "2": "calibration failed", "3": "calibrated"]["${rawValue}"] ?: "unknown (${rawValue})"
                    logText("${device.displayName} calibration status is ${statusText}")
                    sendEvent(name: "calibrationStatus", value: statusText)
                    break

                // Calibration progress, percent
                case "0020":
                    logText("${device.displayName} calibration progress is ${rawValue}%")
                    sendEvent(name: "calibrationProgress", value: rawValue, unit: "%")
                    break

                // Active power (Watts, scaled by 1000)
                case "7006":
                    currentPower = (rawValue / 1000.0).toDouble().round(2)
                    logDebug("Power: reported=${currentPower}, ghost=${ghostPower}, threshold=${powerThreshold}")

                    currentPower = (currentPower - ghostPower).toDouble().round(2)
                    logDebug("Power adjusted: ${currentPower}")

                    if (currentPower <= powerThreshold) {
                        logDebug("Power below threshold, suppressing to zero")
                        currentPower = 0.0
                    }

                    logText("${device.displayName} power is ${currentPower} W")
                    sendEvent(name: "power", value: currentPower, unit: "W")
                    powerUpdated = true
                    break

                // Voltage (scaled by 1000)
                case "7005":
                    currentVoltage = (rawValue / 1000.0).toDouble().round(1)
                    logText("${device.displayName} voltage is ${currentVoltage} V")
                    sendEvent(name: "voltage", value: currentVoltage, unit: "V")
                    voltageUpdated = true
                    break

                case "7004":
                    logDebug("Raw attribute 7004: ${rawValue}")
                    break
            }
        }
        catch (Exception e) {
            logError("Failed handling Sonoff attribute: ${e.message}")
        }
    }

    if ((powerUpdated || voltageUpdated) && currentVoltage > 0) {
        double calculatedAmps = (currentPower / currentVoltage).toDouble().round(3)
        logText("${device.displayName} current is ${calculatedAmps} A")
        sendEvent(name: "amperage", value: calculatedAmps, unit: "A")
    }
}

def on() {
    logDebug("Turning <b>on</b>")

    List<String> cmds = []
    float transitionSeconds = defaultTransition != null ? defaultTransition.toFloat() : DEFAULT_TRANSITION_RATE
    int tenthsOfSecond = Math.round(transitionSeconds * 10)

    if (state.currentDuration != transitionSeconds && dynamicTransitions) {
        logDebug("Dynamic transition change triggered: adjusting hardware configuration to ${tenthsOfSecond} (${transitionSeconds} s)")
        cmds += zigbee.writeAttribute(0xFC11, 0x001F, 0x23, tenthsOfSecond)   // Push configuration commands ahead of the dim command
        state.currentDuration = transitionSeconds
    }

    cmds += zigbee.on()
    return cmds
}

def off() {
    logDebug("Turning <b>off</b>")

    List<String> cmds = []
    float transitionSeconds = defaultTransition != null ? defaultTransition.toFloat() : DEFAULT_TRANSITION_RATE
    int tenthsOfSecond = Math.round(transitionSeconds * 10)

    if (state.currentDuration != transitionSeconds && dynamicTransitions) {
        logDebug("Dynamic transition change triggered: adjusting hardware configuration to ${tenthsOfSecond} (${transitionSeconds} s)")
        cmds += zigbee.writeAttribute(0xFC11, 0x001F, 0x23, tenthsOfSecond)   // Push configuration commands ahead of the dim command
        state.currentDuration = transitionSeconds
    }

    cmds += zigbee.off()
    return cmds
}

def setLevel(level, duration = null) {
    if (level < 0) {
        level = 0
    }
    if (level > 100) {
        level = 100
    }

    logDebug("Setting <b>level</b> to ${level}%")

    List<String> cmds = []

    float targetSeconds = (duration != null) ? duration.toFloat() : ((defaultTransition != null) ? defaultTransition.toFloat() : DEFAULT_TRANSITION_RATE)
    int tenthsOfSecond = Math.round(targetSeconds * 10)

    if (state.currentDuration != targetSeconds && dynamicTransitions) {
        logDebug("Dynamic transition change triggered: adjusting hardware configuration to ${tenthsOfSecond} (${targetSeconds} s)")
        cmds += zigbee.writeAttribute(0xFC11, 0x001F, 0x23, tenthsOfSecond)   // Push configuration commands ahead of the dim command
        state.currentDuration = targetSeconds
    }

    cmds += zigbee.setLevel(level)

    // Keep local UI states aligned
    state.lastLevel = level
    sendEvent(name: "level", value: level, unit: "%")
    if (level > 0) {
        sendEvent(name: "switch", value: "on")
    }

    return cmds
}

def startCalibration() {
    logDebug("Starting <b>calibration</b>")
    return ["he wattr 0x${device.deviceNetworkId} 0x01 0xFC11 0x001D 0x42 {03010101}"]
}

def stopCalibration() {
    logDebug("Stopping <b>calibration</b>")
    return ["he wattr 0x${device.deviceNetworkId} 0x01 0xFC11 0x001D 0x42 {03010102}"]
}

def refresh() {
    logDebug("Refreshing...")

    List<String> cmds = zigbee.onOffRefresh() +
                        zigbee.levelRefresh() +
                        zigbee.readAttribute(SONOFF_CLUSTER_ID_HEX, [0x7004, 0x7005, 0x7006]) +   // Power reporting
                        zigbee.readAttribute(SONOFF_CLUSTER_ID_HEX, 0x001E) +                     // Calibration status
                        zigbee.readAttribute(SONOFF_CLUSTER_ID_HEX, 0x0020) +                     // Calibration progress
                        zigbee.readAttribute(SONOFF_CLUSTER_ID_HEX, 0x0016) +                     // Switch mode
                        zigbee.readAttribute(SONOFF_CLUSTER_ID_HEX, 0x4003) +                     // Switch duration
                        zigbee.readAttribute(0x0006, 0x4003)                                      // Power-on behavior
    return delayBetween(cmds, 50)
}

def installed() {
    configure()
}

def configure() {
    logDebug("Configuring...")

    // Normal core control configurations mixed with manufacturer registration hooks
    def configCmds = zigbee.onOffConfig() +
                     zigbee.levelConfig() +
                     zigbee.configureReporting(SONOFF_CLUSTER_ID_HEX, 0x7004, 0x23, 1, 3600, 1, [mfgCode: SONOFF_MFG_ID]) +
                     zigbee.configureReporting(SONOFF_CLUSTER_ID_HEX, 0x7005, 0x23, 5, 3600, 1000, [mfgCode: SONOFF_MFG_ID]) +
                     zigbee.configureReporting(SONOFF_CLUSTER_ID_HEX, 0x7006, 0x23, 5, 3600, 50, [mfgCode: SONOFF_MFG_ID]) +
                     "delay 1000"

    return configCmds + refresh()
}

def updated() {
    logDebug("Updated...")
    logDebug("${device.displayName} Debug logging is <b>${logEnable}</b>, description text logging is <b>${txtEnable}</b>")

    if (logEnable) {
        runIn(86400, logsOff, [overwrite: true])   // Turn off debug logging after 24 hours
        logDebug("Debug logging will be automatically switched off after 24 hours")
    }
    else {
        unschedule(logsOff)
    }


    List<String> cmds = []

    String powerOnString = powerOnBehavior ?: POWER_ON_BEHAVIOR_PREVIOUS
    int powerOnVal = 0xFF
    switch (powerOnString) {
        case POWER_ON_BEHAVIOR_OFF:
            powerOnVal = 0x00
            break
        case POWER_ON_BEHAVIOR_ON:
            powerOnVal = 0x01
            break
        case POWER_ON_BEHAVIOR_TOGGLE:
            powerOnVal = 0x02
            break
    }
    logDebug("Power-on state: ${powerOnString} (${powerOnVal})")
    cmds += zigbee.writeAttribute(0x0006, 0x4003, 0x30, powerOnVal)


    float transitionSeconds = defaultTransition != null ? defaultTransition.toFloat() : DEFAULT_TRANSITION_RATE
    int tenthsOfSecond = Math.round(transitionSeconds * 10)

    logDebug("Default transition speed: ${tenthsOfSecond} (${transitionSeconds} s)")
    cmds += zigbee.writeAttribute(SONOFF_CLUSTER_ID_HEX, 0x001F, 0x23, tenthsOfSecond)
    state.currentDuration = transitionSeconds


    String switchModeString = switchMode ?: SWITCH_MODE_TRIPLE
    int switchModeVal = 0x04
    switch (switchModeString) {
        case SWITCH_MODE_DUAL:
            switchModeVal = 0x03
            break
        case SWITCH_MODE_MOMENTARY:
            switchModeVal = 0x01
            break
        case SWITCH_MODE_TOGGLE:
            switchModeVal = 0x00
            break
    }
    logDebug("External switch mode: ${switchModeString} (${switchModeVal})")
    cmds += zigbee.writeAttribute(SONOFF_CLUSTER_ID_HEX, 0x0016, 0x20, switchModeVal)


    int switchRateVal = switchRate != null ? switchRate.toInteger() : DEFAULT_SWITCH_RATE
    logDebug("External switch fade rate: ${switchRateVal}")
    cmds += zigbee.writeAttribute(SONOFF_CLUSTER_ID_HEX, 0x4003, 0x20, switchRateVal)

    cmds += "delay 5000"
    cmds += refresh()

    return cmds
}

def logsOff() {
    logDebug("Debug logging disabled")
    device.updateSetting("logEnable", [value:"false", type:"bool"])
}

def logDebug(msg) {
    if (logEnable) {
        log.debug "${msg}"
    }
}

def logError(msg) {
    log.error "${msg}"
}

def logText(msg) {
    if (txtEnable) {
        log.info "${msg}"
    }
}
