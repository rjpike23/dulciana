;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.control.core
  (:require [cljs.core.async :as async]
            [taoensso.timbre :as log :include-macros true]
            [tubax.helpers :as xml-util]
            [dulciana.service.net :as net]
            [dulciana.service.store :as store]
            [dulciana.service.upnp.control.messages :as msg]
            [util :as util]))

(defn send-control-request [url service-type action-name params]
  (let [result-chan (async/chan 1 (comp (map msg/control-response-parse)
                                        (map msg/analyze-control-response)))
        msg (msg/emit-control-request service-type action-name params)
        hdrs {"USER-AGENT" "Unix/5.0 UPnP/2.0 dulciana/1.0"
              "SOAPACTION" (str "\"" service-type "#" action-name "\"")}]
    (async/pipe (net/send-http-request "POST" url hdrs msg {}) result-chan)))

(defn handle-control-request [req res]
  (let [usn (.-usn (.-params req))
        parsed-req (msg/control-request-parse (.-body req))
        elt (-> parsed-req :content first :content first :tag)
        params (into {}
                     (map (fn [x] [(:tag x) (xml-util/text x)])
                          (-> parsed-req :content :content :content)))]
    (if (and parsed-req elt params)
      (let [action-name (first elt)
            svc-type (second elt)
            device (store/find-local-device (store/get-dev-id usn))
            result (store/invoke-action device action-name params)]
        (if result
          (. res (send (msg/emit-control-response svc-type action-name result)))
          (. res (sendStatus 500))))
      (. res (sendStatus 400)))))
