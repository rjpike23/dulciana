;  Copyright 2017-2018, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.control.core
  (:require [cljs.core.async :as async]
            [dulciana.service.net :as net]
            [dulciana.service.upnp.control.messages :as msg]
            [taoensso.timbre :as log :include-macros true]))

(defn send-control-request [url service-type action-name params]
  (let [result-chan (async/chan 1 (comp (map msg/control-response-parse)
                                        (map msg/analyze-control-response)))
        msg (msg/emit-control-request service-type action-name params)
        hdrs {"USER-AGENT" "Unix/5.0 UPnP/2.0 dulciana/1.0"
              "SOAPACTION" (str "\"" service-type "#" action-name "\"")}]
    (log/info "send-control-request" url service-type action-name params msg hdrs)
    (async/pipe (net/send-http-request "POST" url hdrs msg {}) result-chan)))

(defn handle-control-request [req res]
  (msg/control-request-parse (.-body req)))
