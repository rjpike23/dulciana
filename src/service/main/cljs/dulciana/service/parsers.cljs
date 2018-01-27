;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.parser
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [clojure.string :as str]
            [clojure.zip :as zip]
            [cljs.core.async :as async]
            [instaparse.core :as parser :refer-macros [defparser]]
            [tubax.core :as xml]
            [tubax.helpers :as xml-util]
            [taoensso.timbre :as log :include-macros true]))

(defparser ssdp-parser
  (str/join "\n"
            '("SSDP_MSG = START_LINE HEADERS (<CRLF>+ BODY)?"
              "CRLF = '\\r\\n'"
              "SP = ' '"
              "REQUEST_URI = #'[^ ]*'" ; Be forgiving as possible in parsing
              "START_LINE = NOTIFY | SEARCH | SUBSCRIBE | UNSUBSCRIBE | RESPONSE"
              "NOTIFY = 'NOTIFY' <SP> REQUEST_URI <SP> 'HTTP/1.1'"
              "SEARCH = 'M-SEARCH' <SP> REQUEST_URI <SP> 'HTTP/1.1'"
              "SUBSCRIBE = 'SUBSCRIBE' <SP> REQUEST_URI <SP> 'HTTP/1.1'"
              "UNSUBSCRIBE = 'UNSUBSCRIBE' <SP> REQUEST_URI <SP> 'HTTP/1.1'"
              "RESPONSE = 'HTTP/1.1 200 OK'"
              "HEADERS = (HEADER *)"
              "SEPARATOR = ':' #'[ \t]*'"
              "HEADER = (<CRLF> NAME <SEPARATOR> VALUE)"
              "NAME = #'[\\w\\-_\\.]+'"
              "VALUE = #'[^\\r]*'"
              "BODY = #'.*'")))

(defn ssdp-parse [channel-msg]
  (let [parse-result (ssdp-parser (:message channel-msg))]
    (when (parser/failure? parse-result)
      (log/error "Error" parse-result)
      (throw (js/Error. parse-result)))
    (assoc channel-msg :message parse-result)))

(defn error-handler [ex]
  (.log js/console "Exception parsing msg" ex))

(defn header-map [hdrs-ast]
  (into {} (map #(let [[HEADER [NAME name] [VALUE value]] %] [(str/lower-case name) value])
                hdrs-ast)))

(defn ssdp-analyzer [parse-result]
  (let [[SSDP_MSG [START_LINE [type]] [HEADERS & headers] body] (:message parse-result)]
    (log/debug "SSDP msg type" type)
    (assoc parse-result :message {:type type
                                  :headers (header-map headers)
                                  :body body})))

(defonce ssdp-message-channel (atom nil))
(defonce ssdp-publisher (atom nil))

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
      (assoc channel-msg :message (xml/xml->clj (:message channel-msg))))
    (catch :default e
      (log/debug channel-msg)
      (.log js/console "ERROR in xml-parse" e))))

(defn xml-pair [node]
  [(:tag node) (xml-util/text node)])

(defn xml-map
  "Returns a function that converts an xml->clj data structure into a map {:<tag-name> <content>},
  according to the supplied spec. spec is a map from a tag keyword to a function of a single argument.
  When a child node with a tag name appearing in the spec map is found, the corresponding function is
  called with the node as argument. The return value is used as the <content> in the resulting map."
  [spec]
  (fn [node]
    (into {} (reduce (fn [out child]
                       (if-let [spec-fun (spec (:tag child))]
                         (cons [(:tag child) (spec-fun child)] out)
                         out))
                     '()
                     (node :content)))))

(defn xml-list [spec]
  (fn [node]
    (reduce (fn [out child]
              (if-let [spec-fun (spec (child :tag))]
                (cons (spec-fun child) out)
                out))
            '()
            (node :content))))

(defn analyze-device-descriptor [desc]
  ((xml-map
    {:specVersion (xml-map
                   {:major xml-util/text :minor xml-util/text})
     :device (xml-map
              {:deviceType xml-util/text
               :friendlyName xml-util/text
               :manufacturer xml-util/text
               :manufacturerURL xml-util/text
               :modelDescription xml-util/text
               :modelName xml-util/text
               :modelNumber xml-util/text
               :serialNumber xml-util/text
               :UDN xml-util/text
               :iconList (xml-list
                          {:icon (xml-map
                                  {:mimetype xml-util/text
                                   :width xml-util/text
                                   :height xml-util/text
                                   :depth xml-util/text
                                   :url xml-util/text})})
               :serviceList (xml-list
                             {:service (xml-map
                                        {:serviceId xml-util/text
                                         :serviceType xml-util/text
                                         :SCPDURL xml-util/text
                                         :controlURL xml-util/text
                                         :eventSubURL xml-util/text})})})})
   desc))

(defn analyze-service-descriptor [desc]
  ((xml-map {:specVersion (xml-map
                           {:major xml-util/text :minor xml-util/text})
             :actionList (xml-list
                          {:action (xml-map
                                    {:name xml-util/text
                                     :argumentList (xml-list
                                                    {:argument (xml-map
                                                                {:name xml-util/text
                                                                 :direction xml-util/text
                                                                 :relatedStateVariable xml-util/text
                                                                 :retval (constantly true)})})})})
             :serviceStateTable (xml-list
                                 {:stateVariable (xml-map
                                                  {:name xml-util/text
                                                   :datatype xml-util/text
                                                   :defaultValue xml-util/text
                                                   :allowedValueRange (xml-map
                                                                       {:minimum xml-util/text
                                                                        :maximum xml-util/text
                                                                        :step xml-util/text})
                                                   :allowedValueList (xml-list
                                                                      {:allowedValue xml-util/text})})})})
   desc))

(defn analyze-descriptor [channel-msg]
  (if (:error channel-msg)
    channel-msg ; push the original object through the pipeline.
    (let [desc (:message channel-msg)]
                                        ;(log/debug desc)
      (assoc channel-msg
             :message (case (:tag desc)
                        :root (analyze-device-descriptor desc)
                        :scpd (analyze-service-descriptor desc)
                        desc)))))

(defn descriptor-descriminator [msg]
  (if (:service-info msg)
    :service
    :device))

(defonce descriptor-channel (atom nil))
(defonce descriptor-publisher (atom nil))

(defn start-ssdp-parser []
  (reset! ssdp-message-channel
          (async/chan 1
                      (comp (map ssdp-parse) (map ssdp-analyzer))
                      error-handler))
  (reset! ssdp-publisher
          (async/pub @ssdp-message-channel (comp :type :message)))
  (reset! descriptor-channel
          (async/chan 1
                      (comp (map descriptor-parse) (map analyze-descriptor))
                      error-handler))
  (reset! descriptor-publisher
          (async/pub @descriptor-channel descriptor-descriminator)))

(defn stop-ssdp-parser []
  (async/close! @ssdp-message-channel)
  (async/close! @descriptor-channel))
