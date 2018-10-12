;  Copyright 2017-2018, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.control.core
  (:require [dulciana.service.net :as net]
            [dulciana.service.upnp.control.messages :as msg]))

(defn send-control-request [url service-type action-name params]
  (let [msg (msg/emit-control-request service-type action-name params)
        hdrs {"USER-AGENT" "Unix/5.0 UPnP/2.0 dulciana/1.0"
              "SOAPACTION" (str "\"" service-type "#" action-name "\"")}]
    (net/send-http-request "POST" url {:body msg
                                       :headers hdrs} {})))
