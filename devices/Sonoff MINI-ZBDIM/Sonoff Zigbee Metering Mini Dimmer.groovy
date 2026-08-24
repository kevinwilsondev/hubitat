metadata {
    definition (name: "Sonoff Zigbee Metering Mini Dimmer", namespace: "kevinwilsondev.hubitat", author: "Kevin Wilson") {
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
        input name: "switchMode", type: "enum", title: "External switch type", defaultValue: "Triple Button", options: ["Triple Button", "Dual Button", "Single Momentary", "Single Toggle"]
        input name: "switchRate", type: "number", title: "External switch fade rate", defaultValue: 4, range: "1..5"
        input name: "powerOnBehavior", type: "enum", title: "Power-on behavior", defaultValue: "Previous State", options: ["Previous State", "Off", "On", "Toggle Previous"]
        input name: "defaultTransition", type: "number", title: "Default transition speed (seconds)", defaultValue: 2.5, range: "0..60"
        input name: "dynamicTransitions", type: "bool", title: "Allow dynamic transition speeds", defaultValue: false
        input name: "standbyGhostPower", type: "number", title: "Standby ghost power (Watts)", defaultValue: 0.07, range: "0..5"
        input name: "standbyPowerThreshold", type: "number", title: "Standby power threshold (Watts)", defaultValue: 0.05, range: "0..5"
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

// Parse incoming Zigbee messages
def parse(String description) {
    logDebug("Parse description: ${description}")

    // Check if this is the custom Sonoff FC11 Cluster
    if (description?.contains("cluster: FC11") || description?.contains("clusterInt: 64529")) {
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

        if ((descMap.clusterInt == 64529 || descMap.clusterId == "FC11") && description.startsWith("catchall:")) {
            logDebug("Sonoff catchall")
        }

        // Power-on behavior (genOnOff cluster, standard attribute 0x4003 "startUpOnOff") - arrives either as an attribute report (command 0A) or a read attributes
        // response (command 01) depending on whether it was pushed or explicitly read via refresh()/configure().
        if (descMap.clusterInt == 6 && descMap.attrId == "4003") {
            String behaviorText = ["00": "Off", "01": "On", "02": "Toggle Previous", "FF": "Previous State"][descMap.value?.toUpperCase()] ?: "unknown (${descMap.value})"
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
    logDebug("Parsing Sonoff cluster FC11: ${map}")

    def attributes = []
    if (map.attrId) {
        attributes << [id: map.attrId, val: map.value]
    }
    if (map.additionalAttrs) {
        map.additionalAttrs.each { attributes << [id: it.attrId, val: it.value] }
    }

    double currentVoltage = device.currentValue("voltage") ?: 235.0
    double currentPower = device.currentValue("power") ?: 0.0
    float ghostPower = standbyGhostPower ? standbyGhostPower.toFloat() : 0.0
    float powerThreshold = standbyPowerThreshold ? standbyPowerThreshold.toFloat() : 0.5
    boolean powerUpdated = false
    boolean voltageUpdated = false

    attributes.each { attr ->
        try {
            long rawValue = Long.parseLong(attr.val, 16)

            logDebug("Sonoff cluster, ${attr.id?.toUpperCase()}=${rawValue} (${attr.val})")

            switch (attr.id?.toUpperCase()) {
                // External switch mode
                case "0016":
                    String statusText = ["0": "Single Toggle", "1": "Single Momentary", "3": "Dual Button", "4": "Triple Button"]["${rawValue}"] ?: "unknown (${rawValue})"
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
    float transitionSeconds = defaultTransition != null ? defaultTransition.toFloat() : 2.5
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
    float transitionSeconds = defaultTransition != null ? defaultTransition.toFloat() : 2.5
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

    float targetSeconds = (duration != null) ? duration.toFloat() : ((defaultTransition != null) ? defaultTransition.toFloat() : 2.5)
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
                        zigbee.readAttribute(0xFC11, [0x7004, 0x7005, 0x7006]) +   // Power reporting
                        zigbee.readAttribute(0xFC11, 0x001E) +                     // Calibration status
                        zigbee.readAttribute(0xFC11, 0x0020) +                     // Calibration progress
                        zigbee.readAttribute(0xFC11, 0x0016) +                     // Switch mode
                        zigbee.readAttribute(0xFC11, 0x4003) +                     // Switch duration
                        zigbee.readAttribute(0x0006, 0x4003)                       // Power-on behavior
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
                     zigbee.configureReporting(0xFC11, 0x7004, 0x23, 1, 3600, 1, [mfgCode: "0x128C"]) +
                     zigbee.configureReporting(0xFC11, 0x7005, 0x23, 5, 3600, 1000, [mfgCode: "0x128C"]) +
                     zigbee.configureReporting(0xFC11, 0x7006, 0x23, 5, 3600, 50, [mfgCode: "0x128C"]) +
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

    String powerOnString = powerOnBehavior ?: "Previous State"
    int powerOnVal = 0xFF
    switch (powerOnString) {
        case "Off":
            powerOnVal = 0x00
            break
        case "On":
            powerOnVal = 0x01
            break
        case "Toggle Previous":
            powerOnVal = 0x02
            break
    }
    logDebug("Power-on state: ${powerOnString} (${powerOnVal})")
    cmds += zigbee.writeAttribute(0x0006, 0x4003, 0x30, powerOnVal)


    float transitionSeconds = defaultTransition != null ? defaultTransition.toFloat() : 2.5
    int tenthsOfSecond = Math.round(transitionSeconds * 10)

    logDebug("Default transition speed: ${tenthsOfSecond} (${transitionSeconds} s)")
    cmds += zigbee.writeAttribute(0xFC11, 0x001F, 0x23, tenthsOfSecond)
    state.currentDuration = transitionSeconds


    String switchModeString = switchMode ?: "Triple Button"
    int switchModeVal = 0x04
    switch (switchModeString) {
        case "Dual Button":
            switchModeVal = 0x03
            break
        case "Single Momentary":
            switchModeVal = 0x01
            break
        case "Single Toggle":
            switchModeVal = 0x00
            break
    }
    logDebug("External switch mode: ${switchModeString} (${switchModeVal})")
    cmds += zigbee.writeAttribute(0xFC11, 0x0016, 0x20, switchModeVal)


    int switchRateVal = switchRate != null ? switchRate.toInteger() : 4
    logDebug("External switch fade rate: ${switchRateVal}")
    cmds += zigbee.writeAttribute(0xFC11, 0x4003, 0x20, switchRateVal)

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
