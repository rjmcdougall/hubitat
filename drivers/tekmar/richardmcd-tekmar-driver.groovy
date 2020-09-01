/***********************************************************************************************************************
 *
 *  A Hubitat Driver using TCP to RS232 gateway on the local network to connect to the tekmar 482 
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
 *  Name: Tekmar 482 Driver
 *. Author: Richard McDougall
 *  Created April, 2020
 *
 *  Protocol Reference: http://www.tekmarcontrols.com/products/accessories/482-firmware-update/7-products/219-tekmar-home-automation-protocol.html
 *
 *  TODO:
 *     - change fan mode
 *     - properly parse messages containing 0x35
 *     - checksum incoming messages
 *     - add some lag to coalesce temp change settings when hitting button multiple times
 *.    - make temp adj to a +/- 1f on the actual decimal current
 *     - outdoor temp as temperature sensor
 *     - what to do with floor temp measurements and setpoints?
 *
 *
 ***********************************************************************************************************************/

public static String version() { return "v0.1" }

import groovy.transform.Field
import hubitat.helper.HexUtils

metadata {
	definition(name: "Tekmar 482 Driver", namespace: "proto", author: "Richard McDougall") {
		capability "Initialize"
        capability "Configuration"
        //capability "Thermostat"
        capability "TemperatureMeasurement"

		command "refreshTemperatureStatus"
	}
	preferences {
		input name: "ip", type: "text", title: "IP Address", required: true
		input name: "port", type: "number", title: "Port", range: 1..65535, required: true, defaultValue: 2101
		input name: "timeout", type: "number", title: "Timeout in minutes", range: 0..1999, defaultValue: 0
        input name: "pollInterval", type: "number", title: "Pollinterval in seconds", range: 0..1999, defaultValue: 20
		input name: "discoveryEnable", type: "bool", title: "Enable Thermostat Discovery", defaultValue: true
		input name: "tempCelsius", type: "bool", title: "Temperatures in ˚C", defaultValue: false
		input name: "dbgEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input ( name: "configLoggingLevelIDE", title: "IDE Live Logging Level:\nMessages with this level and higher will be logged to the IDE.", type: "enum",
            options: [
                "0" : "None",
                "1" : "Error",
                "2" : "Warning",
                "3" : "Info",
                "4" : "Debug",
                "5" : "Trace"
            ],
            defaultValue: "5", displayDuringSetup: true, required: false )   
	}
}

// Update this list with friendly names for your tstats
// First run with discovery enable, then edit list
@Field final tstatNames = [ 
        0 : "None",
                    201 : 'Family',
                    202 : 'Master',
                    203 : 'Master Bath',
                    204 : 'Max',
                    205 : 'Living',
                    206 : 'Loft',
                    207 : 'Madison',
                    208 : 'Sam',
                    209 : 'Garage',
                    210 : 'Shop',
                    2001 : 'Patio (2)',
                    2002 : 'DHW (2)',
                    2018 : 'Pool House (2)',
                    3001 : 'Patio (3)',
                    3002 : 'DHW (3)',
                    3018 : 'Pool House (3)'
];

//general handlers
def installed() {
	log.warn "${device.label} installed..."
	initialize()
}

def updated() {
	log.info "${device.label} Updated..."
	if (dbgEnable)
		log.debug "${device.label}: Configuring IP: ${ip}, Port ${port}, Timeout: ${timeout}"
	initialize()
}



def initialize() {
	if (port == null)
		device.updateSetting("port", [type: "number", value: 2101])
	if (timeout == null)
		device.updateSetting("timeout", [type: "number", value: 0])
	if (tempCelsius == null)
		device.updateSetting("tempCelsius", [type: "bool", value: "false"])
	if (dbgEnable == null)
		device.updateSetting("dbgEnable", [type: "bool", value: "false"])
	if (discoveryEnable == null)
		device.updateSetting("discoveryEnable", [type: "bool", value: "false"])
    if (pollInterval == null)
		device.updateSetting("pollInterval", [type: "number", value: "10"])
	telnetClose()
	boolean success = true
    state.partialMessageCount = 0
	try {
        interfaces.rawSocket.connect(ip, port.toInteger(), 
                                     byteInterface: true,
								     //readDelay: 1)
                                     eol: 53)
		pauseExecution(1000)
		if (dbgEnable)
			log.debug "${device.label}: Socket connection to Tekmar 482 established"
	} catch (e) {
		log.warn "${device.label}: initialize error: ${e.message}"
		success = false
	}
	if (success) {
		heartbeat()
		refresh()
	}
}

def configure() {
    log.debug "${device.label} Executing 'configure()'"
    state.loggingLevelIDE = (settings.configLoggingLevelIDE) ? settings.configLoggingLevelIDE.toInteger() : 3

    unschedule()
    schedule("0/${settings.pollInterval} * * * * ? *", refresh)
    
    refresh()
    childDevices.each {
        try
        {
            it.setLoggingLevel(state.loggingLevelIDE)
        }
        catch (e) {}    
    }
}


def poll() {
    refresh()
}

def refresh() {
    enableReporting()
}




def uninstalled() {
	interfaces.rawSocket.close()
	removeChildDevices(getChildDevices())
}

private removeChildDevices(delete) {
	delete.each { deleteChildDevice(it.deviceNetworkId) }
}



// Telnet
def telnetTimeout() {
	telnetStatus("timeout")
}

int getReTry(boolean inc) {
	int reTry = (state.reTryCount ?: 0).toInteger()
	if (inc) reTry++
	state.reTryCount = reTry
	return reTry
}

def socketStatus(String status) {
	log.warn "${device.label} socketStatus error: ${status}"
	if (status == "receive error: Stream is closed" || status == "send error: Broken pipe (Write failed)" || status == "timeout") {
		getReTry(true)
		log.error "Socket connection dropped..."
		log.warn "${device.label} Socket is restarting..."
		initialize()
	}
}

def heartbeat() {
	if (timeout != null && timeout.toString().isInteger() && timeout >= 1)
		runIn(timeout * 60, "telnetTimeout")
}

def dump_hex(bytes) {
   def s = "";
   for (Byte b : bytes) {
       s = s + String.format("%02X ", b);
   }
   return s;
}


private String unhexify(String hexStr) {
  StringBuilder output = new StringBuilder("");

  for (int i = 0; i < hexStr.length(); i += 2) {
    String str = hexStr.substring(i, i + 2);
    output.append((char) (Integer.parseInt(str, 16) & 0xFF));
  }

  return output.toString();
}


def unpack(message) {
    def s = "";
    def lastChar = 0;
    for (char b: message) {
        if ((lastChar.compareTo(tekmarTokens('ESC')) == 0) || b.compareTo(tekmarTokens('ESC')) != 0) {
            s = s + b;
        }
        lastChar = b;
    }
    return s;
}


def serviceName(service) {
    
    def services = [ 0x00 : 'Update',
                     0x01 : 'Request',
                     0x02 : 'Report',
                     0x03 : 'Response:Update',
                     0x04 : 'Response:Request'
                ];
    return services[service];
}

@Field final  tekmarMethods = [ 
                0x000 : 'NullMethod',
                0x107 : 'NetworkError',
                0x10F : 'ReportingState',
                0x117 : 'OutdoorTemp',
                0x11F : 'DeviceAttributes',
                0x127 : 'ModeSetting',
                0x12F : 'ActiveDemand',
                0x137 : 'CurrentTemperature',
                0x138 : 'CurrentFloorTemp',
                0x13F : 'HeatSetpoint',
                0x147 : 'CoolSetpoint',
                0x14F : 'SlabSetpoint',
                0x157 : 'FanPercent',
                0x15F : 'TakingAddress',
                0x167 : 'DeviceInventory',
                0x16F : 'SetbackEnable',
                0x177 : 'SetbackState',
                0X17F : 'SetbackEvents',
                0x187 : 'FirmwareRevision',
                0x18F : 'ProtocolVersion',
                0x197 : 'DeviceType',
                0x19F : 'DeviceVersion',
                0x1A7 : 'DateTime',
                0x13D : 'SetpointGroupEnable',
                0x13E : 'SetpointDevice',
                0x150 : 'RelativeHumidity',
                0x151 : 'HumidityMax',
                0x152 : 'HumidityMin'];

def methodName(Integer method) {
        return tekmarMethods[method];
}

def methodNum(String methodName) {
    return (tekmarMethods.collectMany{ k,v -> 
        (v == methodName) ? [k] : []}[0] as Integer)
}

def methodAddressField(String method) {
   def methodAddress = [ 
        'NullMethod'        :   0,
        'NetworkError'      :   0,
        'ReportingState'    :   0,
        'OutdoorTemp'       :   0,
        'DeviceAttributes'  :   8,
        'ModeSetting'       :   8,
        'ActiveDemand'      :   8,
        'CurrentTemperature':   8,
        'CurrentFloorTemp'  :   8,
        'HeatSetpoint'      :   8,
        'CoolSetpoint'      :   8,
        'SlabSetpoint'      :   8,
        'FanPercent'        :   8,
        'TakingAddress'     :   8,
        'DeviceInventory'   :   8,
        'SetbackEnable'     :   0,
        'SetbackState'      :   8,
        'SetbackEvents'     :   8,
        'FirmwareRevision'  :   0,
        'ProtocolVersion'   :   0,
        'DeviceType'        :   8,
        'DeviceVersion'     :   8,
        'DateTime'          :   0,
        'SetpointGroupEnable':  0,
        'SetpointDevice'    :   8,
        'RelativeHumidity'  :   8,
        'HumidityMax'       :   8,
        'HumidityMin'       :   8
    ];
    return methodAddress[method];
}

def methodTempField(String method) {
   def methodTemp = [ 
        'NullMethod'        :   0,
        'NetworkError'      :   0,
        'ReportingState'    :   0,
        'OutdoorTemp'       :   8,
        'DeviceAttributes'  :   0,
        'ModeSetting'       :   0,
        'ActiveDemand'      :   0,
        'CurrentTemperature':   10,
        'CurrentFloorTemp'  :   10,
        'HeatSetpoint'      :   0,
        'CoolSetpoint'      :   0,
        'SlabSetpoint'      :   0,
        'FanPercent'        :   0,
        'TakingAddress'     :   0,
        'DeviceInventory'   :   0,
        'SetbackEnable'     :   0,
        'SetbackState'      :   0,
        'SetbackEvents'     :   0,
        'FirmwareRevision'  :   0,
        'ProtocolVersion'   :   0,
        'DeviceType'        :   0,
        'DeviceVersion'     :   0,
        'DateTime'          :   0,
        'SetpointGroupEnable':  0,
        'SetpointDevice'    :   0,
        'RelativeHumidity'  :   0,
        'HumidityMax'       :   0,
        'HumidityMin'       :   0
    ];
    return methodTemp[method];
}

def methodHumidityField(String method) {
   def methodHumidity = [ 
        'NullMethod'        :   0,
        'NetworkError'      :   0,
        'ReportingState'    :   0,
        'OutdoorTemp'       :   8,
        'DeviceAttributes'  :   0,
        'ModeSetting'       :   0,
        'ActiveDemand'      :   0,
        'CurrentTemperature':   0,
        'CurrentFloorTemp'  :   0,
        'HeatSetpoint'      :   0,
        'CoolSetpoint'      :   0,
        'SlabSetpoint'      :   0,
        'FanPercent'        :   0,
        'TakingAddress'     :   0,
        'DeviceInventory'   :   0,
        'SetbackEnable'     :   0,
        'SetbackState'      :   0,
        'SetbackEvents'     :   0,
        'FirmwareRevision'  :   0,
        'ProtocolVersion'   :   0,
        'DeviceType'        :   0,
        'DeviceVersion'     :   0,
        'DateTime'          :   0,
        'SetpointGroupEnable':  0,
        'SetpointDevice'    :   0,
        'RelativeHumidity'  :   10,
        'HumidityMax'       :   10,
        'HumidityMin'       :   10
    ];
    return methodHumidity[method];
}


def tekmarAddress(String message, Integer offset) {
    byte addrLow = message.charAt(offset);
    byte addrHigh = message.charAt(offset + 1);
    Integer addr = 256 * (addrHigh & 0xFF) + (addrLow & 0xFF);
    return addr;
}

def tekmarTemp(String message, Integer offset) {
    byte tempLow = message.charAt(offset);
    byte tempHigh = message.charAt(offset + 1);
    Float degH = 256 * (tempHigh & 0xFF) + (tempLow & 0xFF);
    // degH = 10*(degF) + 850
    Float tempF = (degH - 850) / 10
    return tempF;
}

@Field final Map tekmarTokenList = [ 
        'SOM'    :   0xCA,
        'TYPE'   :   0x06,
        'ESC'    :   0x2F,
        'EOM'    :   0x35
    ];

@Field final Map tekmarEscapedTokenList = [ 
        'SOM'    :   0xCA,
        'ESC'    :   0x2F,
        'EOM'    :   0x35
    ];

def tekmarTokens(String token) {
    return tekmarTokenList[token];
}


def methodTempEField(String method) {
   def methodTempE = [ 
        'NullMethod'        :   0,
        'NetworkError'      :   0,
        'ReportingState'    :   0,
        'OutdoorTemp'       :   8,
        'DeviceAttributes'  :   0,
        'ModeSetting'       :   0,
        'ActiveDemand'      :   0,
        'CurrentTemperature':   0,
        'CurrentFloorTemp'  :   0,
        'HeatSetpoint'      :   11,
        'CoolSetpoint'      :   11,
        'SlabSetpoint'      :   11,
        'FanPercent'        :   0,
        'TakingAddress'     :   0,
        'DeviceInventory'   :   0,
        'SetbackEnable'     :   0,
        'SetbackState'      :   0,
        'SetbackEvents'     :   0,
        'FirmwareRevision'  :   0,
        'ProtocolVersion'   :   0,
        'DeviceType'        :   0,
        'DeviceVersion'     :   0,
        'DateTime'          :   0,
        'SetpointGroupEnable':  0,
        'SetpointDevice'    :   0,
        'RelativeHumidity'  :   0,
        'HumidityMax'       :   0,
        'HumidityMin'       :   0
    ];
    return methodTempE[method];
}

// Tekmar DegE Conversions
def tekmarFromTempE(String message, Integer offset) {
    byte tempLow = message.charAt(offset);
    Float degE = (tempLow & 0xFF);
    // degF = 10*(degF) + 850
    Float tempF = ((degE / 2) * 1.8) + 32
    return tempF;
}

def tekmarToTempE(tempF) {
    // degE = 2 *(degC)
    Integer degE = Math.round(2 * ((tempF - 32) / 1.8))
    return degE;
}

@Field static Map tekmarDemands = [ 
        0: 'Off',
        1: 'Heat',
        2: 'Unknown-2',
        3: 'Cool'
    ];

def methodDemandField(demand) {
    if (demand < 4) {
        return tekmarDemands[demand];
    } else {
        return ("Unknown Mode " + demand);
    }
}

def methodDeviceCapabilityField(capability) {
   def methodDeviceCapability = [ 
        0: 'Off',
        2: 'Heat',
        4: 'Cool',
        8: 'Slab'
    ];
    return methodDeviceCapability[capability];
}

def tekmarDemand(String message, Integer offset) {

    byte demand = message.charAt(offset);
    demandStr = "error in message";
    if (demand < 4) {
       demandStr = methodDemandField(demand & 0xff);
    }
    return demandStr;
}

def tekmarRelativeHumidity(String message, Integer offset) {
    byte rh = message.charAt(offset);
    return rh;
}

@Field final Map tekmarModes = [ 
        0: 'off',
        1: 'heat',
        2: 'auto',
        3: 'cool',
        4: 'vent',
        6: 'emergency'
    ];

def methodModeField(mode) {
    return tekmarModes[mode];
}

def modeNum(mode) {
    return (tekmarModes.collectMany{ k,v -> (v == mode) ? [k] : []}[0] as Integer)
}


def tekmarMode(String message, Integer offset) {

    byte mode = message.charAt(offset);
    //log.debug "${device.label} mode  : $mode";
    modeStr = "error in message";
    if (mode < 7) {
       modeStr = methodModeField(mode & 0xff);
    } else {
        modeStr = String.format("Mode %d", mode)
    }
    return modeStr;
}

def tstatName(Integer addr) {
        return tstatNames[addr];
}
    
// Tekmar 482 Event Receipt Lines
def parse(String message) {
    
    def pck
    
    if (state.partialMessage != null) {
        log.info("${device.label} prior partial message: $state.partialMessage");
        log.info "${device.label} This RAW message     :   $message";
        pck = hubitat.helper.HexUtils.hexStringToByteArray(state.partialMessage + message)                      
        def raw = dump_hex(pck);
        log.info "${device.label} New RAW message      :   $raw";
        state.partialMessage = null;
    } else {
        pck = hubitat.helper.HexUtils.hexStringToByteArray(message)                      
    }
    
    def unpacked = unpack(pck);
    packedSize = pck.size();
    unpackedSize = unpacked.size();

    // Decode header
    byte bodyLength = unpacked.charAt(1);
    expectedSize = unpackedSize - 5;
    
    if (dbgEnable) {
        def raw = dump_hex(pck);
        def m = dump_hex(unpacked);
        log.debug "${device.label} Parsing RAW      message    :   $raw ($packedSize)";
		log.debug "${device.label} Parsing Unpacked message    :   $m ($unpackedSize)";
		log.debug "${device.label} Parsing message             :   $m (budylength $bodyLength, expected $expectedSize)";
    }
    
    if (bodyLength > expectedSize) {
        log.info "${device.label} oversized message       :   $m (bodylength $bodyLength, expected $expectedSize)";
        return;
    }
    
    // Occurs when packed contains a data value that triggers socket
    // Read completion (0x35)
    if (expectedSize < bodyLength) {
        log.info("${device.label} partial message")
        def raw = dump_hex(pck);
             def m = dump_hex(unpacked);
             def p = dump_hex(state.partialMessage);
    		 log.info "${device.label} Partial message           :   $m (bodylength $bodyLength, expected $expectedSize)";
             log.info "${device.label} Partial RAW      message  :   $raw ($packedSize)";
	    	 log.info "${device.label} Partial Unpacked message  :   $m ($unpackedSize)";
	    	 log.info "${device.label} Previous packed message   :   $p";
             state.partialMessage = message
             state.partialMessageCount = state.partialMessageCount + 1
            return
    }

    byte rpcType = unpacked.charAt(2);
    if (rpcType != 6) {
        log.debug "${device.label} not a tekmar message    :  $raw";
        return
    }
    
    // Decode service/method (1 byte)
    byte service1 = unpacked.charAt(3);
    Integer service = service1 * 1;
    def serviceName = serviceName(service)
    
    // Method is 4 bytes
    byte methodHigh = unpacked.charAt(5);
    byte methodLow = unpacked.charAt(4);
    Integer method = 256 * methodHigh + methodLow;
    def methodName = methodName(method)
    
    if (dbgEnable) {
        log.debug "${device.label} method  : " + String.format("%04X:%s, %04X:%s [%s]", service, serviceName, method, methodName, m);
    }
    
    HashMap data = [:];
    
    // Tekmar method  : 0003:Response:Update, 0127:ModeSetting
    // CA 08 06 03 27 01 00 00 CE 00 73 7A 35 
    
    // If the message has a Tekmar Address, extract it
    if ((offset = methodAddressField(methodName)) > 1) {
        addr = tekmarAddress(unpacked, offset);
        data["tstatId"] = addr;
        data["tstatName"] = tstatName(addr);
    }

    // If the message has a Tekmar temp, extract it
    if ((offset = methodTempField(methodName)) > 1) {
        temp = tekmarTemp(unpacked, offset);
        if (methodName.equals("CurrentFloorTemp")) {
            data["floor"] = temp;
        } else {
            data["temp"] = temp;
        }
        // Send the outdoor temp to this device
        if (methodName.equals("OutdoorTemp")) {
            log.debug "${device.label} outdoor temp  : " + String.format("%f", temp);
            sendEvent(name: "temperature", value: temp as Integer, unit:"F")
            return
        }
    }
    
    // If the message has a Tekmar setpoint temp, extract it
    if ((offset = methodTempEField(methodName)) > 1) {
        setpoint = tekmarFromTempE(unpacked, offset);
        data[methodName] = setpoint;
        // Also kick the same theromostat to report it's mode setting
        // since this doesn't get reported automatically
        if (data.containsKey("tstatId")) {
            queryTstat(data["tstatId"]);
        }
    }
    
    
    // If the message has a Tekmar Demand field, extract it
    if (methodName.equals("ActiveDemand") == true) {
        demand = tekmarDemand(unpacked, 10);
        data["demand"] = demand;
    }
    
    // If the message has a Tekmar relative humidity field, extract it
    if ((offset = methodHumidityField(methodName)) > 1) {
        rh = tekmarRelativeHumidity(unpacked, offset);
        data[methodName] = rh;
    }
    
    // If the message has a Tekmar Mode field, extract it
    if (methodName.equals("ModeSetting") == true) {
        mode = tekmarMode(unpacked, 10);
        data["mode"] = mode;
    } 
    
    
    if (discoveryEnable && data.containsKey("tstatId")) {
        log.debug "${device.label} discoveryEnable  : ${discoveryEnable} tstat: ${tstatId} "
        createTstat(data["tstatId"],  data["tstatName"] + " Climate");
    }
    
    debugStr = methodName;
    if (dbgEnable) {
        if (data.containsKey("tstatId")) {
            debugStr = debugStr + String.format(" address %d:%s", data["tstatId"], data["tstatId"]);
        }
        if (data.containsKey("temp")) {
            debugStr = debugStr + String.format(" temp %3f", temp);
        }
        if (data.containsKey("RelativeHumidity")) {
            debugStr = debugStr + String.format(" humidity %3d", rh);
        }
        if (data.containsKey("HumidityMax")) {
            debugStr = debugStr + String.format(" humidity max %3d", rh);
        }
        if (data.containsKey("HumidityMin")) {
            debugStr = debugStr + String.format(" humidity min %3d", rh);
        }
        if (data.containsKey("coolSetpoint")) {
            debugStr = debugStr + String.format(" coolSetpoint %3f", coolSetpoint);
        }
        if (data.containsKey("demand")) {
            debugStr = debugStr + String.format(" demand %3s", demand);
        }
        if (data.containsKey("mode")) {
            debugStr = debugStr + String.format(" mode %3s", mode);
        }
        log.debug "${device.label} " + debugStr;
    }
    
    updateChildDevice(data);
}


hubitat.device.HubAction sendMsg(hubitat.device.HubAction action = null) {
	return action
}



hubitat.device.HubAction refreshThermostatStatus(String thermostat) {
	if (dbgEnable)
		log.debug "${device.label} refreshThermostatStatus tstat: ${thermostat}"
}


def createTstat(addr, name) {
	String childDeviceNetworkId
    String label
    
    childDeviceNetworkId = "${device.deviceNetworkId}_T_${addr}"

    def newDevice;
    
    if (getChildDevice("${device.deviceNetworkId}_T_${addr}") == null) {
            devname = addr;
            addChildDevice("proto", "Tekmar Thermostat", childDeviceNetworkId, [name: devname, isComponent: false, label: name])
			newDevice = getChildDevice(childDeviceNetworkId)
	        if (dbgEnable)
		        log.debug "${device.label}: create addr: ${addr}, name: ${name}"
    }
    
    newDevice = getChildDevice("${device.deviceNetworkId}_T_${addr}");
    if ((newDevice != null) && (newDevice.label.equals(name) == false)) {
        deleteChildDevice(childDeviceNetworkId)
        addChildDevice("proto", "Tekmar Thermostat", childDeviceNetworkId, [name: addr, isComponent: false, label: name])
        // Maybe use setDisplayName(String displayName) instead?
        if (dbgEnable)
		    log.debug "${device.label}: rename addr: ${addr}, name: ${name}"
    }
    
                                 
}
	
    
def updateChildDevice(data) {
    
    tstatId = data['tstatId'];
    
    def childDevice;
    
    if ((childDevice = getChildDevice("${device.deviceNetworkId}_T_${tstatId}")) != null) {
        if (dbgEnable)
		    log.debug "${device.label}: updating addr: ${tstatId}, child: ${childDevice}"
        childDevice.parse(data);
    }
}


def tekmarChecksum(pck) {
    checksum = 0
    for (b in pck) {
        checksum += (b & 0xff)
    }
    return checksum & 0xFF
}


def stringToBytes(str) {
    Byte[] bytes = new Byte[str.length()]
    def bNum = 0
    for (char b: str) {
        bytes[bNum] = b
        bNum++;
    }
    return bytes;
}


def pack_old(message) {
    def s = "";
    for (char b: message) {
        if (tekmarTokenList.containsValue(b as Integer) == true) {
            s = s + new String(tekmarTokens('ESC') as char);
            //s = s + "foo"
        }
        s = s + b;
    }
    dump = dump_hex(s);    
    //log.debug "${device.label}: send TRPC packed  x ${dump}"
    return stringToBytes(s);
}


def pack(bytes) {
    Byte[] tempBytes = new Byte[bytes.length * 2]
    //def check = ""
    def bNum = 0
    def tempBNum = 0
    for (b in bytes) {
        //check = check + String.format("%X:%b, ", (b & 0xff), tekmarEscapedTokenList.containsValue(b & 0xFF))
        if ((bNum != 0) && (bNum != (bytes.length - 1)) && 
            (tekmarEscapedTokenList.containsValue(b & 0xFF) == true)) {
            tempBytes[tempBNum] = tekmarTokens('ESC')
            tempBNum++
        }
        tempBytes[tempBNum] = b
        bNum++
        tempBNum++
    }
    //log.debug "${device.name} checking ${check}" 
    return tempBytes.getAt([0..(tempBNum-1)])
}


def createTRPC(packet) {
    
    service = packet['service']
    method = packet['method']
    byte[] data = hubitat.helper.HexUtils.hexStringToByteArray(packet['data'])
    len = 5 + data.length
    
    packetlength = len + 5
    Byte[] pck = new Byte[packetlength]
    pck[0] = tekmarTokens('SOM')
    pck[1] = len
    pck[2] = tekmarTokens('TYPE')
    pck[3] = service
    pck[4] = method & 0xFF
    pck[5] = (method >> 8) & 0xFF
    pck[6] = (method >> 16) & 0xFF
    pck[7] = (method >> 24) & 0xFF
    d = 0
    p = 8
    for (b in data) {
        pck[p] = data[d];
        p++;
        d++;
    }
    checksum = tekmarChecksum(pck[1..(pck.length - 3)]);
    pck[p] = checksum
    pck[p + 1] = tekmarTokens('EOM')
    dump = dump_hex(pck);    
    //log.debug "${device.label}: send TRPC unpacked ${dump}"
    packed = pack(pck)
    //dump = dump_hex(packed);    
    //log.debug "${device.label}: send TRPC packed x  ${dump}"
    return packed as byte[]
}
    
    
    

def enableReporting() {
    //CA 05 06 00 0F 01 00 00 1B 35 
    //CA 05 06 00 0F 01 00 00 01 1D 35
    //CA 06 06 00 0F 01 00 00 01 1D 35 
    //pck = unhexify("CA0606000F010000011D35"); 
    //dump = dump_hex(pck);    
    //log.debug "${device.label}:  ${dump}"
    
    //pck = unhexify("CA050600670100007335"); 
    //dump = dump_hex(pck);    
    //log.debug "${device.label}:  ${dump}"
    
    //pck = "CA0606000F010000011D35";
    
    def packet = [
        'service' : 0,
        'method' :  0x10F,//methodNum('ReportingState'),
        'data' : "01"
        ]
    // CA 09 06 02 38 01 00 00 CB 00 E6 05 00 35 
    
    pck = createTRPC(packet)
    //dump = dump_hex(pck);    
    //log.debug "${device.label}:  ${dump}"

    //sendMsg(pck);
    interfaces.rawSocket.sendMessage(hubitat.helper.HexUtils.byteArrayToHexString(pck))
}


// To request the mode of thermostat with address 0001:
// Byte Index	0	1	2	3	4	5	6	6	7	8	9	10	11	12
// Content	     SOF	Length	Type	Service	Method(0)	Method(1)	Method(2)	Method(3)	Data(0)	Data(1)	Data(3)	Data(4)	CS	EOF
// Hex Value	0xca	0x09	0x06	0x01	0x27	   0x01	        0x00	    0x00	    0x01	0x00	0x00	0x00	0x39	0x35

def queryTstat(addrStr) {
    
    addr = addrStr as int
    // Query modes
    def data = String.format("%02X%02X0000", addr & 0xff, (addr >> 8) & 0xff)
    def packet = [
        'service' : 1,
        'method' :  0x127,//methodNum('ReportingState'),
        'data' : data
        ]
    pck = createTRPC(packet)
    //dump = dump_hex(pck);    
    //log.debug "${device.label}:  ${dump}"
    interfaces.rawSocket.sendMessage(hubitat.helper.HexUtils.byteArrayToHexString(pck))
    
    // Query humidity
    data = String.format("%02X%02X0000", addr & 0xff, (addr >> 8) & 0xff)
    packet = [
        'service' : 1,
        'method' :  methodNum('RelativeHumidity'),
        'data' : data
        ]
    pck = createTRPC(packet)
    //dump = dump_hex(pck);    
    //log.debug "${device.label}:  ${dump}"
    interfaces.rawSocket.sendMessage(hubitat.helper.HexUtils.byteArrayToHexString(pck))    
    
}


def setPoint(addrStr, temp, setBackState, mode) {
    addr = addrStr as int
    
    if (mode.equals("Heat")) {
        method = methodNum('HeatSetpoint')
    } else {
        method = methodNum('CoolSetpoint')
    }
    
    if (method == null) {
        return
    }
    
    //log.debug "${device.label} setPoint ${method}"
        
    def data = String.format("%02X%02X%02X%02X", 
                             addr & 0xff, (addr >> 8) & 0xff,
                             setBackState,
                             tekmarToTempE(temp))
    
    def packet = [
        'service' : 0,
        'method' :  method,
        'data' : data
        ]
    
    pck = createTRPC(packet)
    dump = dump_hex(pck);    
    //log.debug "${device.label}:  ${dump}"

    interfaces.rawSocket.sendMessage(hubitat.helper.HexUtils.byteArrayToHexString(pck))
}

def setMode(addrStr, modeStr) {
    addr = addrStr as int
    
    method = methodNum('ModeSetting')
    
    if (method == null) {
        return
    }
    
    //log.debug "${device.label} setMode ${method}"
        
    def data = String.format("%02X%02X%02X", 
                             addr & 0xff, (addr >> 8) & 0xff,
                             modeNum(modeStr))
    
    def packet = [
        'service' : 0,
        'method' :  method,
        'data' : data
        ]
    
    pck = createTRPC(packet)
    dump = dump_hex(pck);    
    //log.debug "${device.label}:  ${dump}"

    interfaces.rawSocket.sendMessage(hubitat.helper.HexUtils.byteArrayToHexString(pck))
}


hubitat.device.HubAction setHeatingSetpoint(BigDecimal degrees, String addr) {
	if (dbgEnable)
		log.debug "${device.label} setHeatingSetpoint tstat: ${addr} temperature ${degrees}"
    // Todo: add setback support
    // A value of THA_CURRENT = 0x07 may be used to request the current setpoint instead of specifying the actual setback state.
    setPoint(addr, degrees, 7, "Heat")
}

hubitat.device.HubAction setCoolingSetpoint(BigDecimal degrees, String addr) {
	if (dbgEnable)
		log.debug "${device.label} setCoolingSetpoint tstat: ${addr} temperature ${degrees}"
    // Todo: add setback support
    // A value of THA_CURRENT = 0x07 may be used to request the current setpoint instead of specifying the actual setback state.
    setPoint(addr, degrees, 7, "Cool")
}


hubitat.device.HubAction setThermostatMode(String thermostatmode, String addr) {
	if (dbgEnable)
		log.debug "${device.label} setThermostatMode tstat: ${thermostat} mode ${thermostatmode}"
    setMode(addr, thermostatmode)
}

hubitat.device.HubAction setThermostatFanMode(String fanmode, String addr) {
	if (dbgEnable)
		log.debug "${device.label} setThermostatFanMode tstat: ${thermostat} fanmode ${fanmode}"
}


