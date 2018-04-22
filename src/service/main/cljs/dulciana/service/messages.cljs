;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.messages
  (:require [clojure.string :as str]))

(defn emit-ssdp-request-msg
  "Formats an SSDP request message using the supplied verb/method, address
  and map of headers."
  [verb address headers]
  (str (str/join "\r\n"
                 (cons (str verb " " address " HTTP/1.1")
                       (map (fn [[k v]] (str (str/upper-case (name k)) ": " v)) headers)))
       "\r\n\r\n"))

(defn emit-m-search-msg
  "Constructs a M-SEARCH SSDP discovery message. Returns a string."
  []
  (emit-ssdp-request-msg "M-SEARCH" "*"
                         {:host "239.255.255.250:1900"
                          :man "\"ssdp:discover\""
                          :mx 5
                          :st "ssdp:all"}))

(defn emit-notify-msg [scdp-location notify-type usn]
  (emit-ssdp-request-msg "NOTIFY" "*"
                         {:host "239.255.255.250:1900"
                          :location scdp-location
                          :nt notify-type
                          :nts "ssdp:update"
                          :usn usn}))

(defn emit-subscribe-msg [pub-host pub-path callback-url state-vars]
  (emit-ssdp-request-msg "SUBSCRIBE" (str pub-host pub-path)
                         {:host pub-host
                          :nt "upnp:event"
                          :timeout "Second-30"
                          :statevar state-vars}))

(defn emit-renew-subscription-msg [pub-host pub-path sid]
  (emit-ssdp-request-msg "SUBSCRIBE" (str pub-host pub-path)
                         {:host pub-host
                          :sid sid
                          :timeout "Second-30"}))

(defn emit-unscubscribe-msg [pub-host pub-path sid]
  (emit-ssdp-request-msg "UNSUBSCRIBE" (str pub-host pub-path)
                         {:host pub-host
                          :sid sid}))

(defn emit-prop-val-xml [[n v]]
  (str "<" (name n) ">" v "</" (name n) ">"))

(defn emit-control-soap-msg [service-type action-name params]
  (str "<?xml version=\"1.0\"?>\r\n"
       "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
       "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
       "<s:Body>"
       "<u:" action-name " xmlns:u=\"" service-type "\">"
       (apply str (map emit-prop-val-xml params))
       "</u:" action-name ">"
       "</s:Body>"
       "</s:Envelope>"))

(defn emit-event-prop-xml [[name value]]
  (str "<s:property>"
       (emit-prop-val-xml [name value])
       "</s:property>"))

(defn emit-event-msg [prop-values]
  (str "<?xml version=\"1.0\"?>\r\n"
       "<s:propertyset xmlns:s=\"urn:schemas-upnp-org:event-1-0\">"
       (apply str (map emit-event-prop-xml prop-values))
       "</s:propertyset>"))
