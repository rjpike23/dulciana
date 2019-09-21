;  Copyright 2017-2018, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.core
  (:require [cljs.core.async :as async]
            [clojure.set :as set]
            [taoensso.timbre :as log :include-macros true]
            [express :as express]
            [url :as url]
            [dulciana.service.config :as config]
            [dulciana.service.events :as events]
            [dulciana.service.net :as net]
            [dulciana.service.store :as store]
            [dulciana.service.upnp.control.core :as control]
            [dulciana.service.upnp.discovery.core :as discovery]
            [dulciana.service.upnp.description.core :as description]
            [dulciana.service.upnp.eventing.core :as eventing]))

(defonce *upnp-http-server* (atom nil))

(defn start-upnp-http-server! []
  (when (config/get-value :dulciana-upnp-server-enable)
    (let [upnp-app (express)]
      (doto upnp-app
        (.get "/upnp/devices/:devid/devdesc.xml"
              description/handle-dev-desc-request)
        (.get "/upnp/services/:usn/scpd.xml"
              description/handle-scpd-request)
        (.notify "/upnp/events"
                 eventing/handle-event-notification)
        (.subscribe "/upnp/services/:usn/eventing"
                    eventing/handle-subscribe-request)
        (.unsubscribe "/upnp/services/:usn/eventing"
                      eventing/handle-unsubscribe-request)
        (.post "/upnp/services/:usn/control"
               control/handle-control-request))
      (reset! *upnp-http-server* (.listen upnp-app (config/get-value :dulciana-upnp-server-port))))))

(defn stop-upnp-http-server! []
  (when @*upnp-http-server*
    (.close @*upnp-http-server*)))
 
(defn register-device [device-descriptor]
  (swap! store/*local-devices* assoc (:udn device-descriptor) device-descriptor)
  (discovery/queue-device-announcements :notify device-descriptor nil))

(defn deregister-devices [devid]
  (when-let [device (@store/*local-devices* devid)]
    (swap! store/*local-devices* dissoc devid)
    (discovery/queue-device-announcements :goodbye device nil)))

(defn start-upnp-services []
  (start-upnp-http-server!)
  (discovery/start-listeners)
  (discovery/start-notifications)
  (description/start-listeners)
  (eventing/start-event-server))

(defn stop-upnp-services []
  (eventing/stop-event-server)
  (description/stop-listeners)
  (discovery/stop-notifications)
  (discovery/stop-listeners)
  (stop-upnp-http-server!))
