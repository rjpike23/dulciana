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

(defn emit-dd-icon [icon]
  (str/join "\n"
            ["<icon>"
             (emit-xml-parameter "mimetype" (:mimetype icon))
             (emit-xml-parameter "width" (:width icon))
             (emit-xml-parameter "height" (:height icon))
             (emit-xml-parameter "depth" (:depth icon))
             (emit-xml-parameter "url" (:url icon))
             "</icon>"]))

(defn emit-dd-service [service]
  (str/join "\n"
            ["<service>"
             (emit-xml-parameter "serviceType" (:serviceType service))
             (emit-xml-parameter "serviceId" (:serviceId service))
             (emit-xml-parameter "SCPDURL" (:SCPDURL service))
             (emit-xml-parameter "controlURL" (:controlURL service))
             (emit-xml-parameter "eventSubURL" (:eventSubURL service))
             "</service>"]))

(defn emit-device-descriptor [dev]
  (str/join "\n"
            ["<?xml version=\"1.0\"?>"
             (str "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" configId=\"" 1 "\">")
             "<specVersion>"
             "<major>2</major>"
             "<minor>0</minor>"
             "</specVersion>"
             "<device>"
             (emit-xml-parameter "deviceType" (-> dev :device :deviceType))
             (emit-xml-parameter "friendlyName" (-> dev :device :friendlyName))
             (emit-xml-parameter "manufacturer" (-> dev :device :manufacturer))
             (emit-xml-parameter "manufacturerUrl" (-> dev :device :manufacturerURL))
             (emit-xml-parameter "modelDescription" (-> dev :device :modelDescription))
             (emit-xml-parameter "modelName" (-> dev :device :modelName))
             (emit-xml-parameter "modelURL" (-> dev :device :modelURL))
             (emit-xml-parameter "serialNumber" (-> dev :device :serialNumber))
             (emit-xml-parameter "UDN" (-> dev :device :UDN))
             (emit-xml-parameter "UPC" (-> dev :device :UPC))
             (emit-xml-parameter "presentationURL" (-> dev :device :presentationURL))
             "<iconList>" (str/join "\n" (map emit-dd-icon (-> dev :device :iconList))) "</iconList"
             "<serviceList" (str/join "\n" (map emit-dd-service (-> dev :device :serviceList))) "</serviceList>"
             "</device>"]))

(defn emit-scpd-action-arg [arg]
  (str/join "\n"
            ["<argument>"
             (emit-xml-parameter "name" (:name arg))
             (emit-xml-parameter "direction" (:direction arg))
             (emit-xml-parameter "relatedStateVariable" (:relatedStateVariable arg))
             "</argument>"]))

(defn emit-scpd-action [action]
  (str/join "\n"
            ["<action>"
             (emit-xml-parameter "name" (:name action))
             (str/join "\n" (map emit-scpd-action-arg (:argumentList action)))
             "</action>"]))

(defn emit-scpd-state-var-range [range]
  (if range
    (str/join "\n"
              ["<allowedValueRange>"
               (emit-xml-parameter "minimum" (:minimum range))
               (emit-xml-parameter "maximum" (:maximum range))
               (emit-xml-parameter "step" (:step range))
               "</allowedValueRange>"])
    ""))

(defn emit-scpd-state-var-allowed-value [values]
  (if values
    (map (partial emit-xml-parameter "allowedValue") values)
    ""))

(defn emit-scpd-state-var [state-var]
  (str/join "\n"
            [(str "<stateVariable"
                  (if-let [se-attr (:sendEvents state-var)]
                    (str " sendEvents=\"" se-attr "\"")
                    "")
                  (if-let [mc-attr (:multicast state-var)]
                    (str " multicast=\"" mc-attr "\"")
                    "")
                  ">")
             (emit-xml-parameter "name" (:name state-var))
             (emit-xml-parameter "dataType" (:dataType state-var))
             (emit-xml-parameter "defaultValue" (:defaultValue state-var))
             (emit-scpd-state-var-range (:allowedValueRange state-var))
             (emit-scpd-state-var-allowed-value (:allowedValueList state-var))
             "</stateVariable>"]))

(defn emit-scpd [service]
  (str/join "\n"
            ["<?xml version=\"1.0\"?>"
             (str "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\" configId=\"" 1 "\">")
             "<specVersion>"
             "<major>2</major>"
             "<minor>0</minor>"
             "</specVersion>"
             "<actionList>" (str/join "\n" (map emit-scpd-action (:actionList service))) "</actionList>"
             "<serviceStateTable>" (str/join "\n" (map emit-scpd-state-var (:serviceStateTable service))) "</serviceStateTable>"
             "</scpd>"]))

