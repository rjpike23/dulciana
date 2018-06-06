;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.messages
  (:require [clojure.string :as str]))

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
