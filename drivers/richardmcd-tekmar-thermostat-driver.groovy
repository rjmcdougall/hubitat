/***********************************************************************************************************************
 *
 *  A Child Tekmar Thermostat 
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
 *  Name: Tekmar Thermostat
 *  Author: Richard McDougall
 *  Created: April, 2020
 *
 ***********************************************************************************************************************/

public static String version() { return "v0.1" }

import groovy.transform.Field

metadata {
	definition(name: "Tekmar Thermostat", namespace: "proto", author: "Richard McDougall") {
		capability "Actuator"
		capability "Thermostat"
		capability "RelativeHumidityMeasurement"
		capability "Refresh"
		command "setThermostatHoldMode", [[name: "hold*", type: "ENUM", constraints: ["off", "on"]]]
		command "setThermostatTemperature", [[name: "temperature*", description: "1 - 99", type: "NUMBER"]]
		attribute "hold", "string"
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
	sendEvent(name: "supportedThermostatFanModes", value: tekmarThermostatFan.values(), descriptionText: "${device.label} supported Fan Modes")
	sendEvent(name: "supportedThermostatModes", value: tekmarThermostatMode.values(), descriptionText: "${device.label} supported Modes")
}

def parse(String description) {
    log.debug "parse(String description) received ${description}"

	String uom = description.substring(0, 2)
	String mode = tekmarThermostatMode[description.substring(6, 7)]
	String hold = tekmarThermostatHold[description.substring(7, 8)]
	String fan = tekmarThermostatFan[description.substring(8, 9)]
	String cTemp = description.substring(9, 11)
	String hSet = description.substring(11, 13)
	String cSet = description.substring(13, 15)
	String cHumid = description.substring(15, 17)
	String descriptionText
	if (device.currentState("coolingSetpoint")?.value == null || device.currentState("coolingSetpoint").value != cSet) {
		descriptionText = "${device.label} coolingSetpoint is ${cSet}${uom}"
		if (txtEnable)
			log.info descriptionText
		sendEvent(name: "coolingSetpoint", value: cSet, unit: uom, descriptionText: descriptionText)
	}
	if (device.currentState("heatingSetpoint")?.value == null || device.currentState("heatingSetpoint").value != hSet) {
		descriptionText = "${device.label} heatingSetpoint is ${hSet}${uom}"
		if (txtEnable)
			log.info descriptionText
		sendEvent(name: "heatingSetpoint", value: hSet, unit: uom, descriptionText: descriptionText)
	}
	if (cHumid != "00" && (device.currentState("humidity")?.value == null || device.currentState("humidity").value != cHumid)) {
		descriptionText = "${device.label} humidity is ${cHumid}%"
		if (txtEnable)
			log.info descriptionText
		sendEvent(name: "humidity", value: cHumid, unit: "%", descriptionText: descriptionText)
	}
	if (device.currentState("hold")?.value == null || device.currentState("hold").value != hold) {
		descriptionText = "${device.label} hold is ${hold}"
		if (txtEnable)
			log.info descriptionText
		sendEvent(name: "hold", value: hold, descriptionText: descriptionText)
	}
	if (device.currentState("temperature")?.value == null || device.currentState("temperature").value != cTemp) {
		descriptionText = "${device.label} temperature is ${cTemp}${uom}"
		if (txtEnable)
			log.info descriptionText
		sendEvent(name: "temperature", value: cTemp, unit: uom, descriptionText: descriptionText)
	}
	if (device.currentState("thermostatFanMode")?.value == null || device.currentState("thermostatFanMode").value != fan) {
		descriptionText = "${device.label} thermostatFanMode is ${fan}"
		if (txtEnable)
			log.info descriptionText
		sendEvent(name: "thermostatFanMode", value: fan, descriptionText: descriptionText)
	}
	if (device.currentState("thermostatMode")?.value == null || device.currentState("thermostatMode").value != mode) {
		descriptionText = "${device.label} thermostatMode is set to ${mode}"
		if (txtEnable)
			log.info descriptionText
		sendEvent(name: "thermostatMode", value: mode, descriptionText: descriptionText)
	}
	if (mode == Heat || mode == EmergencyHeat) {
		if (device.currentState("thermostatSetpoint")?.value == null || device.currentState("thermostatSetpoint").value != hSet) {
			descriptionText = "${device.label} thermostatSetpoint is ${hSet}${uom}"
			sendEvent(name: "thermostatSetpoint", value: hSet, unit: uom, descriptionText: descriptionText)
		}
	} else if (mode == Cool) {
		if (device.currentState("thermostatSetpoint")?.value == null || device.currentState("thermostatSetpoint").value != cSet) {
			descriptionText = "${device.label} thermostatSetpoint is ${cSet}${uom}"
			sendEvent(name: "thermostatSetpoint", value: cSet, unit: uom, descriptionText: descriptionText)
		}
	} else {
		if (device.currentState("thermostatSetpoint")?.value == null || device.currentState("thermostatSetpoint").value != " ") {
			sendEvent(name: "thermostatSetpoint", value: " ")
		}
	}
}

def parse(List description) {
	log.debug "parse(List description) received ${description}"
	return
}



def parse(HashMap data) {
	log.debug "parse(HashMap data) received ${data}"
    log.debug "parse(HashMap data) device ${device}"

    /*
    displayName = device.getDisplayName(device);
    receivedName = data['tstatName'];
    if ((data['tstatName'] != null) && (receivedName.equals(displayname) == false)) {
        // Rename
        device.setDisplaName(receivedName);
    }
    */

    if (data.containsKey("temp") && (fTemp = data["temp"]) && 
        (device.currentState("temperature")?.value == null || device.currentState("temperature").value != fTemp)) {
		    descriptionText = "${device.label} temperature is ${fTemp}"
		    if (txtEnable)
			    log.info descriptionText
		    sendEvent(name: "temperature", value: fTemp, descriptionText: descriptionText)
	}
    
    if (data.containsKey("demand") && (demand = data["demand"]) && 
        (device.currentState("demand")?.value == null || device.currentState("demand").value != demand)) {
		    descriptionText = "${device.label} demand is ${demand}"
		    if (txtEnable)
			    log.info descriptionText
		    sendEvent(name: "thermostatMode", value: demand, descriptionText: descriptionText)
	}    
    
    if (data.containsKey("CoolSetpoint") && (coolingSetpoint = data["CoolSetpoint"]) && 
        (device.currentState("coolingSetpoint")?.value == null || device.currentState("coolingSetpoint").value != coolingSetpoint)) {
		    descriptionText = "${device.label} CoolSetpoint is ${coolingSetpoint}"
		    if (txtEnable)
			    log.info descriptionText
		    sendEvent(name: "coolingSetpoint", value: CoolSetpoint, descriptionText: descriptionText)
	}    
    
    if (data.containsKey("HeatSetpoint") && (heatingSetpoint = data["HeatSetpoint"]) && 
        (device.currentState("heatingSetpoint")?.value == null || device.currentState("heatingSetpoint").value != heatingSetpoint)) {
		    descriptionText = "${device.label} heatingSetpoint is ${heatingSetpoint}"
		    if (txtEnable)
			    log.info descriptionText
		    sendEvent(name: "heatingSetpoint", value: heatingSetpoint, descriptionText: descriptionText)
	}     
    return
}


def auto() {
	parent.sendMsg(parent.setThermostatMode(Auto, getThermID()))
}

def cool() {
	parent.sendMsg(parent.setThermostatMode(Cool, getThermID()))
}

def emergencyHeat() {
	parent.sendMsg(parent.setThermostatMode(EmergencyHeat, getThermID()))
}

def fanAuto() {
	parent.sendMsg(parent.setThermostatFanMode(Auto, getThermID()))
}

def fanCirculate() {
	parent.sendMsg(parent.setThermostatFanMode(Circulate, getThermID()))
}

def fanOn() {
	parent.sendMsg(parent.setThermostatFanMode(On, getThermID()))
}

def heat() {
	parent.sendMsg(parent.setThermostatMode(Heat, getThermID()))
}

def off() {
	parent.sendMsg(parent.setThermostatMode(Off, getThermID()))
}

def setCoolingSetpoint(BigDecimal degrees) {
	parent.sendMsg(parent.setCoolingSetpoint(degrees, getThermID()))
}

def setHeatingSetpoint(BigDecimal degrees) {
	parent.sendMsg(parent.setHeatingSetpoint(degrees, getThermID()))
}

def setSchedule(schedule) {
}

def setThermostatFanMode(String fanmode) {
	parent.sendMsg(parent.setThermostatFanMode(fanmode, getThermID()))
}

def setThermostatHoldMode(String holdmode) {
	parent.sendMsg(parent.setThermostatHoldMode(holdmode, getThermID()))
}

def setThermostatMode(String thermostatmode) {
	parent.sendMsg(parent.setThermostatMode(thermostatmode, getThermID()))
}

def setThermostatTemperature(BigDecimal degrees) {
	parent.sendMsg(parent.setThermostatTemperature(degrees, getThermID()))
}

def refresh() {
	parent.sendMsg(parent.refreshThermostatStatus(getThermID()))
}

String getThermID() {
	String DNID = device.deviceNetworkId
	return DNID.substring(DNID.length() - 2).take(2)
}

@Field final Map tekmarThermostatMode = 
    ['0': Off, 
     '1': Heat, 
     '2': Cool, 
     '3': Auto, 
     '4': EmergencyHeat]
@Field final Map tekmarThermostatHold = 
    ['0': Off, 
     '1': On]
@Field final Map tekmarThermostatFan = 
    ['0': Auto,
     '1': On]

@Field static final String On = "on"
@Field static final String Off = "off"
@Field static final String Heat = "heat"
@Field static final String Cool = "cool"
@Field static final String Auto = "auto"
@Field static final String Circulate = "circulate"
@Field static final String EmergencyHeat = "emergency heat"
@Field static final String FanAuto = "fan auto"
@Field static final String FanOn = "fan on"
