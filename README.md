# Dulciana
UPnP Device Browser (...and, perhaps someday, media controller/server/player), pre-alpha status.

# Install
There's no fancy install bundle yet, the only way to run the project is to check it out to your local machine, and run `lein cljsbuild once` followed by `node target/fig-service/dulciana_figwheel.js`

The UI is accessed by browsing to http://localhost:3000/

# Usage
Currently, Dulciana should discover any UPnP devices on your network and display them on the front page:

![Devices](docs/Devices.png?raw=true)

Clicking any of the devices brings up the device detail pane, which shows detailed information about the device, as well as a list of the services provided by the selected device:

![Device Detail](docs/DeviceDetail.png?raw=true)

Clicking a service shows a list of Actions and State Variables associated with that service:

![Service Detail](docs/ServiceDetail.png?raw=true)

The actions can be invoked by clicking the `Invoke` button and filling in the parameters in the resulting dialog. On clicking the `Submit` button, the results of the action will be displayed in the Outputs section of the form:

![Invoke Action](docs/InvokeAction.png?raw=true)

# Contributing
PRs and Issue reports are welcome!
