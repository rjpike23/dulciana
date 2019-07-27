;  Copyright 2017-2018, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.control.messages
  (:require [taoensso.timbre :as log :include-macros true]
            [tubax.core :as xml]
            [tubax.helpers :as xml-util]
            [dulciana.service.xml :as dulc-xml]))

(defn control-request-parse
  ""
  [msg]
  (dulc-xml/munge-namespaces
   (xml/xml->clj msg) {}))

(defn control-response-parse
  ""
  [http-msg]
  (try
    (if (not (= (-> http-msg :message :status-code) 200))
      http-msg
      (xml/xml->clj (-> http-msg :message :body)))
    (catch :default e
      (log/error e "Unexpected error parsing control response message" http-msg))))

(defn analyze-control-response
  ""
  [resp]
  (into {} (map (fn [elt] [(:tag elt) (xml-util/text elt)])
                (:content (first (:content (first (:content resp))))))))

(defn emit-prop-val-xml [[n v]]
  (str "<" (name n) ">" v "</" (name n) ">"))

(defn emit-soap-msg [body]
  (str "<?xml version=\"1.0\"?>\r\n"
       "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
       "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
       "<s:Body>"
       body
       "</s:Body>"
       "</s:Envelope>"))

(defn emit-control-request [service-type action-name params]
  (emit-soap-msg
   (str  "<u:" action-name " xmlns:u=\"" service-type "\">"
         (apply str (map emit-prop-val-xml params))
         "</u:" action-name ">")))

(defn emit-control-response [service-type action-name params]
  (emit-soap-msg
   (str "<u:" action-name "Response xmlns:u=\"" service-type "\">"
        (apply str (map emit-prop-val-xml params))
        "</u:" action-name "Response>")))

(defn emit-event-prop-xml [[name value]]
  (str "<s:property>"
       (emit-prop-val-xml [name value])
       "</s:property>"))

(defn emit-event-msg [prop-values]
  (str "<?xml version=\"1.0\"?>\r\n"
       "<s:propertyset xmlns:s=\"urn:schemas-upnp-org:event-1-0\">"
       (apply str (map emit-event-prop-xml prop-values))
       "</s:propertyset>"))
