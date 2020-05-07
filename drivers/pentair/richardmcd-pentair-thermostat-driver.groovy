/***********************************************************************************************************************
 *
 *  A Simple Pentair Thernostat
 *
 *  License:
 *  This program is free software: you can redistribute it and/or modify it under the terms of the GNU
 *  General Public License as published by the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 *  implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 *  for more details.
 *
 *  Name: Pentair Thermostat
 *  Author: Richard McDougall
 *  Created>: 4/23/2020
 *
 ***********************************************************************************************************************/

public static String version() { return "v0.1" }

import groovy.transform.Field

metadata {
	definition(name: "Pentair Simple Thermostat", namespace: "proto", author: "Richard McDougall") {
		capability "Actuator"
		capability "Thermostat"
		capability "Refresh"
        capability "Initialize"
        capability "Configuration"

	}
	preferences {
		input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
	}
}

def installed() {
	"Installed..."
	device.updateSetting("txtEnable", [type: "bool", value: true])
    updated()
	refresh()
}

def uninstalled() {
}

def updated() {
	log.info "Updated..."
	log.warn "${device.label} description logging is: ${txtEnable == true}"
	sendEvent(name: "supportedThermostatFanModes", value: pentairFanMode.values(), descriptionText: "${device.label} supported Fan Modes")
	sendEvent(name: "supportedThermostatModes", value: pentairThermostatMode.values(), descriptionText: "${device.label} supported Modes !")
}

def oldcode() {

    if (data.containsKey("temp") && (fTemp = data["temp"]) && 
        (device.currentState("temperature")?.value == null || device.currentState("temperature").value != fTemp)) {
		    descriptionText = "${device.label} temperature is ${fTemp}"
		    if (txtEnable)
			    log.info descriptionText
		    sendEvent(name: "temperature", value: Math.round(fTemp), descriptionText: descriptionText)
	}
    
    if (data.containsKey("mode") && (mode = data["mode"]) && 
        (device.currentState("mode")?.value == null || device.currentState("mode").value != mode)) {
		    descriptionText = "${device.label} mode is ${mode}"
		    if (txtEnable)
			    log.info descriptionText
		    sendEvent(name: "thermostatMode", value: mode, descriptionText: descriptionText)
	}  
}

// From Pentair parent
def receieveHeatingSetpoint(heatingSetpoint) {
   	descriptionText = "${device.label} HeatSetpoint is ${heatingSetpoint}"
    if (txtEnable) {
	     log.info "${descriptionText}"
    }
	sendEvent(name: "heatingSetpoint", value: heatingSetpoint, descriptionText: descriptionText)
    sendEvent(name: "thermostatSetpoint", value: heatingSetpoint, descriptionText: descriptionText)
} 

// From thermostat up/down button
def setHeatingSetpoint(BigDecimal degrees) {
	parent.updateSetpoint(device,degrees)
}

def setSchedule(schedule) {
}

// From Thermostat mode button
def setThermostatMode(String thermostatmode) {
	parent.heaterSetMode(device, modeNum(thermostatmode))
	//parent.heaterOff(device)
}

def refresh() {
	//parent.sendMsg(parent.refreshThermostatStatus(getThermID()))
    parent.poll()
}

def setTemperature(t) {
	log.debug(device.label + " current temp set to ${t}") 
    sendEvent(name: 'temperature', value: t, unit:"F")    
    log.debug(device.label + " DONE current temp set to ${t}") 
}

def switchToModeID(modeNum) {
    mode = modeToStr(modeNum)
    descriptionText = "${device.label} mode is ${mode}"
	if (txtEnable)
	    log.info descriptionText
	sendEvent(name: "thermostatMode", value: mode, descriptionText: descriptionText)
}

def switchToMode(modeStr) {
    descriptionText = "${device.label} mode is ${modeStr}"
	if (txtEnable)
	    log.info descriptionText
	sendEvent(name: "thermostatMode", value: modeStr, descriptionText: descriptionText)
}

def pumpMode(status) {
    log.debug(device.label + " pump mode ${status}") 

    def stateStr = "idle"
    if (device.currentState("thermostatMode")?.value != null && 
        device.currentState("thermostatMode").value.equals("Heater")) {
        stateStr = "heating"
    }
    sendEvent(name: "thermostatOperatingState", value: stateStr, descriptionText: descriptionText)
    log.debug(device.label + " pump mode ${stateStr}") 
}


def modeNum(mode) {
    return (pentairThermostatMode.collectMany{ k,v -> (v == mode) ? [k] : []}[0] as Integer)
}

def modeToStr(mode) {
    return pentairThermostatMode[mode]
}

@Field final Map pentairThermostatMode =
    [0: 'OFF',
     1: 'Heater',
     2: 'Solar Pref',
     3: 'Solar Only']

@Field final Map pentairFanMode =
    [0: 'OFF',
     1: 'ON']

//if (getParent().getDataValue("includeSolar")=='true') {


