/*

Copyright 2020 - tomw and JustinL

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

-------------------------------------------

Change history:

1.0.3 - tomw - Fix broken suspendAllZones()
1.0.2 - tomw - Added capability "Actuator" to allow running custom commands in Rule Machine
1.0.0 - tomw and JustinL - Initial release
2.0.0 - kurtsanders - Fixed several issues, updated device capability to provide rain sensor 'contactSensor' status

 */
import java.text.SimpleDateFormat
metadata
{
    definition(name: "Hunter Hydrawise Controller", namespace: "tomw", author: "tomw, lnjustin", importUrl: "https://raw.githubusercontent.com/KurtSanders/hubitat-hydrawise/refs/heads/main/drivers/Hunter_Hydrawise_Controller.groovy")
    {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Switch"
        capability "ContactSensor"
        
        attribute "name", "string"
        attribute "serial_number", "string"
        attribute "controllertime", "string"
        
        command "runAllZones", ["number"]
        command "stopAllZones"
        command "suspendAllZones", ["string"]     // format: yyyy-mm-dd
    }
}

preferences
{
    section
    {
        input "api_key", "text", title: "API keys can be obtained from your Hydrawise account under My Account -> Generate API Key.", required: true
        input "controller_id", "text", title: "The unique identifier for your controller. This is required when your account has multiple controllers.", required: false
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

def logsOff(){
    log.warn "Debug logging disabled."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def logDebug(msg) 
{
    if (logEnable)
    {
        log.debug(msg)
    }
}

def updated()
{
    logDebug("Hunter Hydrawise Controller: updated()")
    if (logEnable) runIn(900,logsOff)

    configure()
}

def configure() {
    logDebug("Hunter Hydrawise Controller: configure()")
    
    device.deleteCurrentState("status")

    unschedule()
    state.clear()

    refresh()
    def cronString = "6-18 ? 4-10 * *"
    if (state?.nextpoll > 59) {
        cronString = "0 0/${Math.round(state.nextpoll/60)} ${cronString}"
    } else {
        cronString = "0/${state.nextpoll} 0 ${cronString}"        
    }


    //create all zone devices
    createChildren()
    logDebug("${cronString}, refresh)")
    schedule("${cronString}", refresh)
}

def refresh()
{
    logDebug("Hunter Hydrawise Controller: refresh()")
    if (state.customerdetails==null) state.customerdetails=0
    if(state.customerdetails>9) {
        state.customerdetails=0
        def suffix = "customerdetails.php?api_key=${getApi_key()}"
        parse_customerdetails(httpGetExec(suffix))
    } else {
        state.customerdetails=state.customerdetails+1
    }
    
    // update zone info
    suffix = "statusschedule.php?api_key=${getApi_key()}"
    if(null != getController_id())
    {
        suffix += "&controller_id=${getController_id()}"
    }    
    parse_statusschedule(httpGetExec(suffix))
    
    updateChildren()
    
//    runIn(state.nextpoll, refresh)
}

def on() {
    sendEvent(name: "switch", value: "on")
}

def off() {
    sendEvent(name: "switch", value: "off")
}

def createChildren()
{
    logDebug("Hunter Hydrawise Controller: createChildren()")
    for (relay in getRelays())
    {
        String formattedZoneNumber = String.format("%02d", relay.relay)
        logDebug("Hunter Hydrawise Controller: Checking Relay #${formattedZoneNumber}: '${relay.name}'")
        child = (getChildDevice(relay.relay_id.toString()))
        if (!child) {
            logDebug("Hunter Hydrawise Controller: Creating Zone #${formattedZoneNumber}: '${relay.name}'")
            child = addChildDevice("Hunter Hydrawise Zone", relay.relay_id.toString(), [label:"Zone ${formattedZoneNumber} - ${relay.name.toString()}", isComponent:false, name:"${relay.relay_id.toString()}"])
        }
        logDebug("Hunter Hydrawise Controller: Updating Zone #${formattedZoneNumber}: '${relay.name}'")
        child.updateSetting("api_key", getApi_key())
        child.updateSetting("controller_id", getController_id())
        child.updateSetting("relay_id", relay.relay_id)
        child.updateRelayState(relay)
    }
}

def updateChildren()
{
    // update relays from controller, to reduce API calls. Hydrawise limits to 30 calls in 5 minutes
    logDebug("Hunter Hydrawise Controller: updateChildren()")
    def rainSensor = "closed"
    for (relay in getRelays())
    {
        child = getChildDevice(relay.relay_id.toString())
        if (child) {
            child.updateRelayState(relay)
            if (relay?.stop == 1) rainSensor = "open"
        }
    }
    sendEvent(name: "contact", value: rainSensor)
}

def deleteChildren()
{
    for(child in getChildDevices())
    {
        deleteChildDevice(child.deviceNetworkId)
    }
}

def getRelays()
{
    return state.relays
}

def getApi_key()
{
    return api_key
}

def getController_id()
{
    return controller_id
}


def parse_customerdetails(resp)
{
    logDebug("Hunter Hydrawise Controller: parse_customerdetails()")
    
    if(null == resp)
    {
        return
    }
    
    for (controller in resp.controllers)
    {
        if (controller.controller_id.toString() == controller_id)
        {
            sendEvent(name: "name", value: controller.name)
            sendEvent(name: "serial_number", value: controller.serial_number)
        }
    }
}

def parse_statusschedule(resp)
{
    logDebug("Hunter Hydrawise Controller: parse_statusschedule()")
    
    if(null == resp)
    {
        return
    }
    
    state.lastmessage = resp.message
    state.nextpoll = resp.nextpoll
    state.relays = resp.relays
    state.sensors = resp.sensors
    state.controllertime = resp.time

    EpochDateTime = resp.time
    def sdf = new SimpleDateFormat("MM/dd/yyyy h:mm:ss a")    
    ConvertedDateTime = use(groovy.time.TimeCategory) {
        new Date( 0 ) + EpochDateTime.seconds
    }
    sendEvent(name: "controllertime", value: sdf.format(ConvertedDateTime))

}

def runAllZones(runDuration) {
    def suffix = "setzone.php?action=runall&period_id=999&custom=${runDuration}&api_key=${api_key}"
    if(null != getController_id())
    {
        suffix += "&controller_id=${controller_id}"
    }
    
    resp = httpGetExec(suffix)
    if (resp) {
        logDebug(resp.message)
    }
    refresh()
}

def stopAllZones() {
    def suffix = "setzone.php?action=stopall&api_key=${api_key}"
    if(null != getController_id())
    {
        suffix += "&controller_id=${controller_id}"
    }
    
    resp = httpGetExec(suffix)
    if (resp) {
        logDebug(resp.message)
    }
    refresh()
}

def suspendAllZones(suspendDateStr) {
    logDebug("Hunter Hydrawise Controller: suspendAllZones()")
        
    def pattern = "yyyy-MM-dd"
    def date = (Date.parse(pattern, suspendDateStr)?.getTime()/1000)?.toInteger()
    
    // suspend zone
    def suffix = "setzone.php?action=suspendall&period_id=999&custom=${date}&api_key=${api_key}"
    if(controller_id)
    {
        suffix += "&controller_id=${controller_id}"
    }
    
    resp = httpGetExec(suffix)
    if (resp) {
        logDebug(resp.message)
    }
    refresh()
}

def httpGetExec(suffix)
{
    logDebug("Hunter Hydrawise Controller: httpGetExec(${suffix})")
    
    try
    {
        getString = "https://api.hydrawise.com/api/v1/" + suffix
        httpGet(getString.replaceAll(' ', '%20'))
        { resp ->
            if (resp.data)
            {
                logDebug("resp.data = ${resp.data}")
                return resp.data
            }
        }
    }
    catch (Exception e)
    {
        log.warn "Hunter Hydrawise Controller httpGetExec(${suffix}) failed: ${e.message} resp = ${resp}"
    }
}