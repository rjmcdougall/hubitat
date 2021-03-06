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
        capability "ContactSensor"
        capability "Initialize"
        capability "Configuration"

		command "setThermostatHoldMode", [[name: "hold*", type: "ENUM", constraints: ["off", "on"]]]
		command "setThermostatTemperature", [[name: "temperature*", description: "1 - 99", type: "NUMBER"]]
		attribute "hold", "string"
        attribute "tekmarExactCoolSetpoint", "NUMBER"
        attribute "tekmarExactHeatSetpoint", "NUMBER"
	}
	preferences {
		input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
	}
}

def installed() {
	"Installed..."
	device.updateSetting("txtEnable", [type: "bool", value: true])
    if (txtEnable != "none") {
		log.info "${device.label}: Creating contact sensor deviceNetworkId = ${deviceNetworkId} of type: Contact"
    }
    coolingContactName = $device.name + "_cooling_contact"
    coolingContactLabel = $device.name + " Cooling"
	addChildDevice("hubitat", "Virtual Contact Sensor", deviceNetworkId, [name: coolingContactName, isComponent: false, label: coolingContactLabel])
	def newDevice = getChildDevice(deviceNetworkId)
    updated()
	refresh()
}

def uninstalled() {
}

def updated() {
	log.info "Updated..."
	log.warn "${device.label} description logging is: ${txtEnable == true}"
	sendEvent(name: "supportedThermostatFanModes", value: tekmarThermostatFan.values(), descriptionText: "${device.label} supported Fan Modes")
	sendEvent(name: "supportedThermostatModes", value: tekmarThermostatMode.values(), descriptionText: "${device.label} supported Modes !")
}



def parse(List description) {
	log.debug "parse(List description) received ${description}"
	return
}



def parse(HashMap data) {
	//log.debug "parse(HashMap data) received ${data}"
    //log.debug "parse(HashMap data) device ${device}"

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
		    sendEvent(name: "temperature", value: Math.round(fTemp), descriptionText: descriptionText)
	}
    
    if (data.containsKey("floor") && (fFloor = data["floor"]) && 
        (device.currentValue("floortemperature") != fFloor)) {
		    descriptionText = "${device.label} floor temperature is ${fFloor}"
		    if (txtEnable)
			    log.info descriptionText
		    sendEvent(name: "floortemperature", value: Math.round(fFloor), descriptionText: descriptionText)
	}
    
    if (data.containsKey("RelativeHumidity") && (RH = data["RelativeHumidity"]) && (RH > 0)) {
		    descriptionText = "${device.label} humidity is ${RH}"
		    if (txtEnable)
			    log.info descriptionText
		    sendEvent(name: "humidity", value: Math.round(RH), descriptionText: descriptionText)
            if ((tempF = device.currentValue("temperature")) && (tempF > 0)) {
                // Calculate dewPoint based on https://en.wikipedia.org/wiki/Dew_point
                BigDecimal dewpointF = tempF - (0.36 * (100 - RH));
                descriptionText = "${device.label} dewpoint is ${dewpointF}"
		        if (txtEnable)
			        log.info descriptionText
		        sendEvent(name: "dewpoint", value: Math.round(dewpointF), descriptionText: descriptionText)
            }
	}
    
    if (data.containsKey("mode") && (mode = data["mode"]) && 
        (device.currentState("mode")?.value == null || device.currentState("mode").value != mode)) {
		    descriptionText = "${device.label} mode is ${mode}"
		    if (txtEnable)
			    log.info descriptionText
		    sendEvent(name: "thermostatMode", value: mode, descriptionText: descriptionText)
	}  
    
    // Translate tekmar demand into operating state
    operatingState = [
        "Off"    :    "idle",
        "Heat"   :    "heating",
        "Cool"   :    "cooling",
        ]
    
    if (data.containsKey("demand") && (demand = data["demand"]) && 
        (device.currentState("demand")?.value == null || device.currentState("demand").value != demand)) {
		descriptionText = "${device.label} demand is ${demand}"
		if (txtEnable)
	        log.info descriptionText
        if (operatingState.containsKey(demand)) {
            thermostatOperatingState = operatingState[demand]
        } else {
            thermostatOperatingState = "unknown"
        }
		sendEvent(name: "thermostatOperatingState", value: thermostatOperatingState, descriptionText: descriptionText)
	}
    
    currentMode = "unknown"
    if (device.currentState("thermostatMode")?.value != null) {
        currentMode = device.currentState("thermostatMode").value
        //log.debug "${device.label} thermostatMode is ${currentMode}"
	}
    
    if (data.containsKey("CoolSetpoint") && (coolingSetpoint = data["CoolSetpoint"])) {
        if (device.currentState("coolingSetpoint")?.value == null || 
             device.currentState("coolingSetpoint").value != coolingSetpoint) {
    		descriptionText = "${device.label} CoolSetpoint is ${coolingSetpoint}"
            visibleCoolingSetpoint = Math.round(coolingSetpoint)
            if (txtEnable) {
		        log.info "${device.label} CoolSetpoint is ${visibleCoolingSetpoint}"
                log.info "${device.label} tekmarExactCoolSetpoint is ${coolingSetpoint}"
            }
		    sendEvent(name: "coolingSetpoint", value: visibleCoolingSetpoint, descriptionText: descriptionText)
        }
		sendEvent(name: "tekmarExactCoolSetpoint", value: coolingSetpoint, descriptionText: descriptionText)
	} 

    /*
    if (currentMode.equals("cool") && (visibleCoolingSetpoint != null)) {
    	descriptionText = "${device.label} thermostatSetpoint is ${visibleCoolingSetpoint}"
        //log.debug "${descriptionText}"
        sendEvent(name: "thermostatSetpoint", value: visibleCoolingSetpoint, descriptionText: descriptionText)
    }
    */
   

    if (data.containsKey("HeatSetpoint") && (heatingSetpoint = data["HeatSetpoint"])) {
        if (device.currentState("heatingSetpoint")?.value == null || 
             device.currentState("heatingSetpoint").value != heatingSetpoint) {
    		descriptionText = "${device.label} HeatSetpoint is ${heatingSetpoint}"
            visibleHeatingSetpoint = Math.round(heatingSetpoint)
            if (txtEnable) {
		        log.info "${device.label} HeatSetpoint is ${visibleHeatingSetpoint}"
		        log.info "${device.label} tekmarExactHeatSetpoint is ${heatingSetpoint}"
            }
		    sendEvent(name: "heatingSetpoint", value: visibleHeatingSetpoint, descriptionText: descriptionText)
        }
		sendEvent(name: "tekmarExactHeatSetpoint", value: heatingSetpoint, descriptionText: descriptionText)
	} 

    /*
    if (currentMode.equals("heat") && (visibleHeatingSetpoint != null)) {
    	descriptionText = "${device.label} thermostatSetpoint is ${visibleHeatingSetpoint}"
        //log.debug "${descriptionText}"
        sendEvent(name: "thermostatSetpoint", value: visibleHeatingSetpoint, descriptionText: descriptionText)
    } 
    */

    
    
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
    def heatingSetpoint
    if (device.currentState("heatingSetpoint")?.value == null || 
        (heatingSetpoint = device.currentState("heatingSetpoint").value)) {
		    descriptionText = "${device.label} heatingSetpoint is ${heatingSetpoint}"
		    if (txtEnable)
			    log.info descriptionText
	}     
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
    idTokens = device.deviceNetworkId.split('_')
	return idTokens[2]
}

@Field final Map tekmarThermostatMode = 
    [   0: 'off',
        1: 'heat',
        2: 'auto',
        3: 'cool',
        4: 'vent',
        6: 'emergency']
@Field final Map tekmarThermostatHold = 
    ['0': 'Off', 
     '1': 'On']
@Field final Map tekmarThermostatFan = 
    ['0': 'Off', 
     '1': 'FanOn', 
     '2': 'FanAuto']



