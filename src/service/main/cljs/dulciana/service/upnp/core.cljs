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
            [dulciana.service.events :as events]
            [dulciana.service.net :as net]
            [dulciana.service.upnp.discovery.core :as discovery]
            [dulciana.service.upnp.description.core :as description]
            [dulciana.service.upnp.eventing.core :as eventing]))

(defonce *upnp-http-server* (atom {}))

(def *upnp-app* (express))

(. *upnp-app* (get "/upnp/devices/:devid/devdesc.xml" (fn [req res]
                                                        (log/info "Got dev desc request")
                                                        (. res (send "Thanks")))))
(. *upnp-app* (get "/upnp/services/:usn/scpd.xml" (fn [req res]
                                                    (log/info "Get scpd request")
                                                    (. res (send "Thanks")))))
(. *upnp-app* (notify "/upnp/events" (fn [req res]
                                       (log/info "Got event notify request")
                                       (. res (send "Thanks")))))
(. *upnp-app* (subscribe "/upnp/services/:usn/eventing"
                         (fn [req res]
                           (log/info "Got subscribe request")
                           (. res (send "Thanks")))))
(. *upnp-app* (unsubscribe "/upnp/services/:usn/eventing"
                           (fn [req res]
                             (log/info "Got unsubscribe request")
                             (. res (send "Thanks")))))
(. *upnp-app* (post "/upnp/services/:usn/control"
                    (fn [req res]
                      (log/info "Got control request")
                      (. res (send "Thanks")))))
 
(defn register-device [device-descriptor]
  (swap! discovery/*local-devices* assoc (:udn device-descriptor) device-descriptor)
  (discovery/queue-device-announcements :notify device-descriptor nil))

(defn deregister-devices [devid]
  (when-let [device (@description/*local-devices* devid)]
    (swap! description/*local-devices* dissoc devid)
    (discovery/queue-device-announcements :goodbye device nil)))

(defn start-upnp-services []
  (discovery/start-listeners)
  (discovery/start-notifications)
  (description/start-listeners)
  (eventing/start-event-server))

(defn stop-upnp-services []
  (eventing/stop-event-server)
  (description/stop-listeners)
  (discovery/stop-notifications)
  (discovery/stop-listeners))
