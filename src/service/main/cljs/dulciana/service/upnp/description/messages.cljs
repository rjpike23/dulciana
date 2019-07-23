;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.description.messages
  (:require [clojure.string :as str]
            [dulciana.service.xml :as dul-xml]
            [taoensso.timbre :as log :include-macros true]
            [tubax.core :as xml]
            [tubax.helpers :as xml-util]))

(defn descriptor-parse
  "Analyzes the response received on the descriptor-channel and performs an
  action, depending on the state of the response. If there is no error, it replaces
  the :message XML string with a generic xml-clj parse. Otherwise, the original
  response is pushed through the descriptor publication so approprate action can
  be taken on the application state."
  [channel-msg]
  (try
    (if (:error channel-msg)
      channel-msg ; push the original message with error state through the pipe...
      (assoc channel-msg :message (xml/xml->clj (-> channel-msg :message :body))))
    (catch :default e
      (log/error e "Unexpected error parsing descriptor" channel-msg))))

(defn analyze-device-descriptor
  "Performs an analysis on the supplied parsed device descriptor and converts it into
  a form that is easier to use."
  [desc]
  ((dul-xml/xml-map
    {:specVersion (dul-xml/xml-map
                   {:major xml-util/text :minor xml-util/text})
     :device (dul-xml/xml-map
              {:deviceType xml-util/text
               :friendlyName xml-util/text
               :manufacturer xml-util/text
               :manufacturerURL xml-util/text
               :modelDescription xml-util/text
               :modelName xml-util/text
               :modelNumber xml-util/text
               :serialNumber xml-util/text
               :UDN xml-util/text
               :iconList (dul-xml/xml-list
                          {:icon (dul-xml/xml-map
                                  {:mimetype xml-util/text
                                   :width xml-util/text
                                   :height xml-util/text
                                   :depth xml-util/text
                                   :url xml-util/text})})
               :serviceList (dul-xml/xml-list
                             {:service (dul-xml/xml-map
                                        {:serviceId xml-util/text
                                         :serviceType xml-util/text
                                         :SCPDURL xml-util/text
                                         :controlURL xml-util/text
                                         :eventSubURL xml-util/text})})})})
   desc))

(defn analyze-service-descriptor
  "Performs an analysis on the supplied parsed service descriptor and converts
  it into a form that is easier to use."
  [desc]
  ((dul-xml/xml-map
    {:specVersion (dul-xml/xml-map
                   {:major xml-util/text :minor xml-util/text})
     :actionList (dul-xml/xml-list
                  {:action (dul-xml/xml-map
                            {:name xml-util/text
                             :argumentList (dul-xml/xml-list
                                            {:argument (dul-xml/xml-map
                                                        {:name xml-util/text
                                                         :direction xml-util/text
                                                         :relatedStateVariable xml-util/text
                                                         :retval (constantly true)})})})})
     :serviceStateTable (dul-xml/xml-list
                         {:stateVariable (dul-xml/xml-map
                                          {:sendEvents second
                                           :multicast second
                                           :name xml-util/text
                                           :dataType xml-util/text
                                           :defaultValue xml-util/text
                                           :allowedValueRange (dul-xml/xml-map
                                                               {:minimum xml-util/text
                                                                :maximum xml-util/text
                                                                :step xml-util/text})
                                           :allowedValueList (dul-xml/xml-list
                                                              {:allowedValue xml-util/text})})})})
   desc))

(defn analyze-descriptor
  "Performs an analysis on the supplied message from the descriptor
  channel, breaking the parsed xml structures into easier to manage
  pieces. If the error flag is true, we just pass the original
  object through."
  [channel-msg]
  (if (:error channel-msg)
    channel-msg ; push the original object through the pipeline.
    (let [desc (:message channel-msg)]
      (assoc channel-msg
             :message (case (:tag desc)
                        :root (analyze-device-descriptor desc)
                        :scpd (analyze-service-descriptor desc)
                        desc)))))

(defn emit-xml-parameter [tag value]
  (if value
    (str "<" tag ">" value "</" tag ">")
    ""))

(defn emit-dd-icon [{mime-type :mime-type
                     depth :depth
                     height :height
                     width :width
                     url :url}]
  (str/join "\n"
            ["<icon>"
             (emit-xml-parameter "mimetype" mime-type)
             (emit-xml-parameter "width" width)
             (emit-xml-parameter "height" height)
             (emit-xml-parameter "depth" depth)
             (emit-xml-parameter "url" url)
             "</icon>"]))

(defn emit-dd-service [dev-id
                       {service-id :service-id
                        service-type :service-type}]
  (let [usn (str dev-id "::" service-type)]
    (str/join "\n"
              ["<service>"
               (emit-xml-parameter "serviceType" service-type)
               (emit-xml-parameter "serviceId" service-id)
               (emit-xml-parameter "SCPDURL" (str "/upnp/services/" usn "/scpd.xml"))
               (emit-xml-parameter "controlURL" (str "/upnp/services/" usn "/eventing"))
               (emit-xml-parameter "eventSubURL" (str "/upnp/services/" usn "/control"))
               "</service>"])))

(defn emit-device-descriptor [{boot-id :boot-id
                               config-id :config-id
                               device-list :device-list
                               device-type :device-type
                               friendly-name :friendly-name
                               icon-list :icon-list
                               manufacturer :manufacturer
                               manufacturer-url :manufacturer-url
                               model-description :model-description
                               model-name :model-name
                               model-url :model-url
                               presentation-url :presentation-url
                               serial-number :serial-number
                               service-list :service-list
                               udn :udn
                               upc :upc
                               version :version}]
  (str/join "\n"
            ["<?xml version=\"1.0\"?>"
             (str "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" configId=\"" 1 "\">")
             "<specVersion>"
             "<major>2</major>"
             "<minor>0</minor>"
             "</specVersion>"
             "<device>"
             (emit-xml-parameter "deviceType" device-type)
             (emit-xml-parameter "friendlyName" friendly-name)
             (emit-xml-parameter "manufacturer" manufacturer)
             (emit-xml-parameter "manufacturerUrl" manufacturer-url)
             (emit-xml-parameter "modelDescription" model-description)
             (emit-xml-parameter "modelName" model-name)
             (emit-xml-parameter "modelURL" model-url)
             (emit-xml-parameter "serialNumber" serial-number)
             (emit-xml-parameter "UDN" udn)
             (emit-xml-parameter "UPC" upc)
             (emit-xml-parameter "presentationURL" presentation-url)
             "<iconList>" (str/join "\n" (map emit-dd-icon icon-list)) "</iconList>"
             "<serviceList>" (str/join "\n" (map (partial emit-dd-service udn) service-list)) "</serviceList>"
             "</device>"
             "</root>"]))

(defn emit-scpd-action-arg [{direction :direction
                             name :name
                             retval :retval
                             related-state-variable :related-state-variable}]
  (str/join "\n"
            ["<argument>"
             (emit-xml-parameter "name" name)
             (emit-xml-parameter "direction" direction)
             (emit-xml-parameter "relatedStateVariable" related-state-variable)
             (when retval "<retval/>")
             "</argument>"]))

(defn emit-scpd-action [{argument-list :argument-list
                         name :name}]
  (str/join "\n"
            ["<action>"
             (emit-xml-parameter "name" name)
             "<argumentList>"
             (str/join "\n" (map emit-scpd-action-arg argument-list))
             "</argumentList>"
             "</action>"]))

(defn emit-scpd-state-var-range [{maximum :maximum
                                  minimum :minimum
                                  step :step}]
  (str/join "\n"
            ["<allowedValueRange>"
             (emit-xml-parameter "minimum" minimum)
             (emit-xml-parameter "maximum" maximum)
             (emit-xml-parameter "step" step)
             "</allowedValueRange>"]))

(defn emit-scpd-state-var-allowed-value [values]
  (if values
    (map (partial emit-xml-parameter "allowedValue") values)
    ""))

(defn emit-scpd-state-var [{allowed-value-list :allowed-value-list
                            allowed-value-range :allowed-value-range
                            data-type :data-type
                            default-value :default-value
                            multicast :multicast
                            name :name
                            send-events :send-events}]
  (str/join "\n"
            [(str "<stateVariable"
                  (if-let [se-attr send-events]
                    (str " sendEvents=\"" se-attr "\"")
                    "")
                  (if-let [mc-attr multicast]
                    (str " multicast=\"" mc-attr "\"")
                    "")
                  ">")
             (emit-xml-parameter "name" name)
             (emit-xml-parameter "dataType" data-type)
             (emit-xml-parameter "defaultValue" default-value)
             (emit-scpd-state-var-range allowed-value-range)
             (emit-scpd-state-var-allowed-value allowed-value-list)
             "</stateVariable>"]))

(defn emit-scpd [{action-list :action-list
                  service-state-table :service-state-table}]
  (str/join "\n"
            ["<?xml version=\"1.0\"?>"
             (str "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\" configId=\"" 1 "\">")
             "<specVersion>"
             "<major>2</major>"
             "<minor>0</minor>"
             "</specVersion>"
             "<actionList>" (str/join "\n" (map emit-scpd-action action-list)) "</actionList>"
             "<serviceStateTable>" (str/join "\n" (map emit-scpd-state-var service-state-table)) "</serviceStateTable>"
             "</scpd>"]))

