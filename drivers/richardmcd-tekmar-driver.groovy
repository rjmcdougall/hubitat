/***********************************************************************************************************************
 *
 *  A Hubitat Driver using Telnet on the local network to connect to the tekmar 482 rs232 gateway
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
 ***********************************************************************************************************************/

public static String version() { return "v0.1" }

import groovy.transform.Field
import hubitat.helper.HexUtils

metadata {
	definition(name: "Tekmar 482 Driver", namespace: "proto", author: "Richard McDougall") {
		capability "Initialize"
		capability "Telnet"
        capability "Configuration"
        capability "Thermostat"
		command "refreshTemperatureStatus"
//		command "sendMsg"
	}
	preferences {
		input name: "ip", type: "text", title: "IP Address", required: true
		input name: "port", type: "number", title: "Port", range: 1..65535, required: true, defaultValue: 2101
		input name: "timeout", type: "number", title: "Timeout in minutes", range: 0..1999, defaultValue: 0
        input name: "pollInterval", type: "number", title: "Pollinterval in seconds", range: 0..1999, defaultValue: 10
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
	if (pollInterval == null)
		device.updateSetting("pollInterval", [type: "number", value: "10"])
	telnetClose()
	boolean success = true
	try {
		//open telnet connection
		//telnetConnect([termChars: [53]], ip, port.toInteger(), null, null)
        interfaces.rawSocket.connect(ip, port.toInteger(), 
                                     byteInterface: true,
								     //readDelay: 150,
                                     eol: 53)
		//give it a chance to start
		pauseExecution(1000)
		if (dbgEnable)
			log.debug "${device.label}: Socket connection to Tekmar 482 established"
	} catch (e) {
		log.warn "${device.label}: initialize error: ${e.message}"
		success = false
	}
	if (success) {
		heartbeat() // Start checking for  timeout
		refresh()
	}
}

def configure() {
    log.debug "${device.label} Executing 'configure()'"
    state.loggingLevelIDE = (settings.configLoggingLevelIDE) ? settings.configLoggingLevelIDE.toInteger() : 3
    //updateDeviceNetworkID()

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
    log.debug "${device.label}Executing poll()"
    refresh()
}

def refresh() {
    //log.debug "${device.label} Executing 'refresh()'"
    //List<hubitat.device.HubAction> cmds = []
	//cmds.add(refreshTemperatureStatus())
	//return delayBetween(cmds, 1000)
    
    //socketStatus(String message)
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

def telnetStatus(String status) {
	log.warn "${device.label} telnetStatus error: ${status}"
	if (status == "receive error: Stream is closed" || status == "send error: Broken pipe (Write failed)" || status == "timeout") {
		getReTry(true)
		log.error "Telnet connection dropped..."
		log.warn "${device.label} Telnet is restarting..."
		initialize()
	}
}

def heartbeat() {
	if (timeout != null && timeout.toString().isInteger() && timeout >= 1)
		runIn(timeout * 60, "telnetTimeout")
}

// CA 08 06 02 2F 2F 01 00 00 2F CA 00 00 0A 

def dump_hex(bytes) {
   def s = "";
   for (byte b : bytes) {
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

Integer TEKMAR_TYPE = 0x06;
Integer TEKMAR_SOM  = 0xCA;
Integer TEKMAR_ESC  = 0x2f;
Integer TEKMAR_EOM  = 0x35;

def unpack(message) {
    def s = "";
    def lastChar = 0;
    for (char b: message) {
        if ((lastChar.compareTo(TEKMAR_ESC) == 0) || b.compareTo(TEKMAR_ESC) != 0) {
            s = s + b;
        }
        lastChar = b;
    }
    return s;
}

def tekmarChecksum(pck) {
    checksum = 0
    for (b in pck) {
        checksum += (b & 0xff)
    }
    return checksum & 0xFF
}
        

def createTRPC(packet) {
    service = packet['service']
    method = packet['method']
    data = hubitat.helper.HexUtils.hexStringToByteArray(packet['data'])
    len = 5 + data.length()
    
    packetlength = len + 5
    log.debug("${device.label}: Packet method $method length $len csum $checksum totalpcklen = $packetlength")
    pck = Byte[packetlength] 
    pck[0] = TEKMAR_SOF
    pck[1] = len
    pck[2] = TEKMAR_TYPE
    pck[3] = service
    pck[4] = method & 0xFF
    pck[5] = (method << 8) & 0xFF
    pck[6] = (method << 16) & 0xFF
    d = 0
    p = 7
    for (b in data) {
        pck[p] = data[d];
        p++;
        d++;
    }
    checksum = tekmarChecksum(pck.getAt(1));
    pck[p + 1] = checksum
    pck[p + 2] = TEKMAR_EOM
    return pck
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
    
    def packet
    packet['service'] = 0
    packet['method']  = methodNum('ReportingState')
    packet['data'] = {(byte) 0x01}
    
    pck = createTRPC(packet)
    dump = dump_hex(pck);    
    log.debug "${device.label}:  ${dump}"

    //sendMsg(pck);
    interfaces.rawSocket.sendMessage(pck)
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

def tekmarMethods = [ 
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
    return tekmarMethods.collectMany{ k,v -> (v == methodName) ? [k] : []}
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

def methodDemandField(demand) {
   def methodDemand = [ 
        0: 'Off',
        1: 'Heat',
        3: 'Cool'
    ];
    return methodDemand[demand];
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


def tekmarTempE(String message, Integer offset) {
    byte tempLow = message.charAt(offset);
    Float degE = (tempLow & 0xFF);
    // degH = 10*(degF) + 850
    Float tempF = ((degE / 2) * 1.8) + 32
    return tempF;
}
def tekmarDemand(String message, Integer offset) {

    byte demand = message.charAt(offset);
    demandStr = "error in message";
    if (demand < 4) {
       demandStr = methodDemandField(demand & 0xff);
    }
    return demandStr;
}

def tstatName(Integer addr) {
    def tstatNames = [ 
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
        return tstatNames[addr];
}
    
// Tekmar 482 Event Receipt Lines
def parse(String message) {
    
    pck = hubitat.helper.HexUtils.hexStringToByteArray(message)
    
    def unpacked = unpack(pck);
    
    def raw = dump_hex(pck);
    def m = dump_hex(unpacked);
    
    if (dbgEnable) {
        //log.debug "${device.label} byte " + s;
        //s = dump_hex(message);
        //u = dump_hex(unpacked);
		log.debug "${device.label} Parsing Incoming message    :   $raw";
		log.debug "${device.label} Parsing Incoming message    :   $m";
    }
    
    // Decode header
    byte bodyLength = unpacked.charAt(1);
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
        //log.debug "${device.label} service : " + String.format("%04X: %s", service, serviceName);
        log.debug "${device.label} method  : " + String.format("%04X:%s, %04X:%s", service, serviceName, method, methodName);
    }
    
    HashMap data = [:];
    
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
        if (methodName.equals("OutdoorTemp")) {
            data["tstatId"] = 1000;
            data["tstatName"] = 'Outdoor';
        }
    }
    
        
    // If the message has a Tekmar set temp, extract it
    if ((offset = methodTempEField(methodName)) > 1) {
        setpoint = tekmarTempE(unpacked, offset);
        data[methodName] = setpoint;
    }
    
    
    // If the message has a Tekmar Demand field, extract it
    if (methodName.equals("ActiveDemand") == true) {
        demand = tekmarDemand(unpacked, 10);
        data["demand"] = demand;
    }
    
    
    
    debugStr = methodName;
    if (dbgEnable) {
        if (data.containsKey("tstatId")) {
            debugStr = debugStr + String.format(" address %d:%s", data["tstatId"], data["tstatId"]);
            createTstat(data["tstatId"],  data["tstatName"] + " Climate");
        }
        if (data.containsKey("temp")) {
            debugStr = debugStr + String.format(" temp %3f", temp);
        }
        if (data.containsKey("coolSetpoint")) {
            debugStr = debugStr + String.format(" coolSetpoint %3f", coolSetpoint);
        }
        if (data.containsKey("demand")) {
            debugStr = debugStr + String.format(" demand %3s", demand);
        }
        log.debug "${device.label} " + debugStr;
    }
    
    updateChildDevice(data);
}



hubitat.device.HubAction sendMsg(String msg) {
	//return new hubitat.device.HubAction(msg, hubitat.device.Protocol.TELNET)
    //return new sendMessage(String msg)
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
            //addChildDevice("hubitat", "Virtual Temperature Sensor", childDeviceNetworkId, [name: tstatName, isComponent: false, label: tstatName])
            addChildDevice("proto", "Tekmar Thermostat", childDeviceNetworkId, [name: devname, isComponent: false, label: name])
			newDevice = getChildDevice(childDeviceNetworkId)
	        if (dbgEnable)
		        log.debug "${device.label}: create addr: ${addr}, name: ${name}"
    }
    
    newDevice = getChildDevice("${device.deviceNetworkId}_T_${addr}");
    if ((newDevice != null) && (newDevice.label.equals(name) == false)) {
        deleteChildDevice(childDeviceNetworkId)
        addChildDevice("proto", "Tekmar Thermostat", childDeviceNetworkId, [name: devname, isComponent: false, label: name])
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
    


