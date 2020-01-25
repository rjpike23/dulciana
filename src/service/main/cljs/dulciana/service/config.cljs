;  Copyright 2019-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.config
  (:require [taoensso.timbre :as log :include-macros true]))

(def *default*
  {:dulciana-port 3000
   :dulciana-upnp-server-enable true
   :dulciana-upnp-server-port 3001
   :dulciana-upnp-announcement-interval 90000
   :dulciana-upnp-throttle-interval 50
   :dulciana-init-local-devices {}
   :ssdp-mcast-addresses {"IPv4" "239.255.255.250"
                          "IPv6" "ff0e::c"}
   :ssdp-mcast-port 1900
   :ssdp-protocols {"IPv4" "udp4"
                    "IPv6" "udp6"}})

(defonce *config* (atom *default*))

(defn reset-config
  ([] (reset-config *default*))
  ([config] (reset! *config* config)))

(defn merge-config [new-config]
  (swap! *config* merge new-config))

(defn get-value [path]
  (if (sequential? path)
    (get-in @*config* path)
    (@*config* path)))

(defn read-config-string [edn-string])
