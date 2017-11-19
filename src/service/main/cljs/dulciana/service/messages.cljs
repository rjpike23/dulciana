;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.messages
  (:require [clojure.string :as str]))

(defn emit-ssdp-request-msg [verb address headers]
  (str (str/join "\r\n"
                 (cons (str verb " " address " HTTP/1.1")
                       (map (fn [[k v]] (str (str/upper-case (name k)) ": " v)) headers)))
       "\r\n\r\n"))

(defn emit-m-search-msg []
  (emit-ssdp-request-msg "M-SEARCH" "*" {:host "239.255.255.250:1900"
                                         :man "\"ssdp:discover\""
                                         :mx 5
                                         :st "ssdp:all"}))

(defn emit-notify-msg [])

(defn emit-subscribe-msg [pub-host pub-path callback-url state-vars]
  (emit-ssdp-request-msg "SUBSCRIBE" (str pub-host pub-path) {:host pub-host
                                                              :nt "upnp:event"
                                                              :timeout "Second-30"
                                                              :statevar state-vars}))

(defn emit-renew-subscription-msg [pub-host pub-path sid]
  (emit-ssdp-request-msg "SUBSCRIBE" (str pub-host pub-path) {:host pub-host
                                                              :sid sid
                                                              :timeout "Second-30"}))

(defn emit-unscubscribe-msg [pub-host pub-path sid]
  (emit-ssdp-request-msg "UNSUBSCRIBE" (str pub-host pub-path) {:host pub-host
                                                                :sid sid}))

(defn emit-control-msg-param [[name value]]
  (str "<" name ">" value "</" name ">"))

(defn emit-control-soap-msg [service-type action-name params]
  (str "<?xml version=\"1.0\"?>\n\r"
       "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
       "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
       "<s:Body>"
       "<u:" action-name " xmlns:u=\"" service-type "\">"
       (apply str (map emit-control-msg-param params))
       "</u:" action-name ">"
       "</s:Body>"
       "</s:Envelope>"))

(defn emit-event-soap-msg [])
