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

1.0.0 - @tomw and @JustinL - Initial release
2.0.0 - kurtsanders Updated depreciated attributes, removed the automatic deletion via 'Configuration' of zones when controller is unavailable from cloud

 */
import java.text.SimpleDateFormat

metadata
{
    definition(name: "Hunter Hydrawise System", namespace: "tomw", author: "tomw", importUrl: "https://raw.githubusercontent.com/KurtSanders/hubitat-hydrawise/refs/heads/main/drivers/Hunter_Hydrawise_System.groovy")
    {
        capability "Configuration"
        capability "Refresh"
        attribute "name", "string"
    }
    command "deleteAllChildrenZones", [[name: "Danger Zone", description: "This will permanatly delete all Hubitat children zone devices"]]
}

preferences
{
    section
    {
        input "api_key", "text", title: "API keys can be obtained from your Hydrawise account under My Account -> Generate API Key.", required: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def logDebug(msg) 
{
    if (logEnable)
    {
        log.debug(msg)
    }
}

void deleteAllZones() {
    // clear out existing devices
    deleteChildren()
    // clear existing state info
    state.clear() 
}


def configure()
{
    logDebug("Hunter Hydrawise System: configure()")
    
   
    // update system info
    refresh()
    
    //create all controller devices (User must remove unused or outdated zones)
    createChildren()
}

def refresh()
{
    logDebug("Hunter Hydrawise System: refresh()")
    
    // update controller info
    def suffix = "customerdetails.php?api_key=${getApi_key()}"
    parse_customerdetails(httpGetExec(suffix))
}

def updated()
{
    logDebug("Hunter Hydrawise System: updated()")
    configure()
}

def uninstalled()
{
    logDebug("Hunter Hydrawise System: uninstalled()")
    for(child in getChildDevices())
    {
        deleteChildDevice(child.deviceNetworkId)
    }
}

def createChildren()
{
    logDebug("Hunter Hydrawise System: createChildren()")
    for (controller in getControllers())
    {
        try {
            child = addChildDevice("Hunter Hydrawise Controller", controller.controller_id.toString(), [label:"${controller.serial_number.toString()}", isComponent:false, name:"${controller.controller_id.toString()}"])
            child.updateSetting("api_key", getApi_key())
            child.updateSetting("controller_id", controller.controller_id)
            child.configure()
        } catch (Exception e) {
	        log.warn "Hunter Hydrawise System Zone ${controller.serial_number.toString()} Exists: ${e.message}"
    	}
    }
}

def deleteChildren()
{
    for(child in getChildDevices())
    {
        deleteChildDevice(child.deviceNetworkId)
    }
}

def parse_customerdetails(resp)
{
    if (resp == null) {
        logDebug("Hunter Hydrawise System: parse_customerdetails() resp=${resp}")
        return
    }
    logDebug("Hunter Hydrawise System: parse_customerdetails()")
    
    state.activecontroller_id = resp.controller_id
    state.customer_id = resp.customer_id
    state.controllers = resp.controllers
    sendEvent(name: "name", value: resp.controllers[0].name)
}

def getControllers()
{
    return state.controllers
}

def getApi_key()
{
    return api_key
}

def httpGetExec(suffix)
{
    logDebug("Hunter Hydrawise System: httpGetExec(${suffix})")
    
    try
    {
        getString = "https://api.hydrawise.com/api/v1/" + suffix
        logDebug("getString = ${getString}")
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
        log.warn "Hunter Hydrawise System httpGetExec() failed: ${e.message}"
    }
}
    
