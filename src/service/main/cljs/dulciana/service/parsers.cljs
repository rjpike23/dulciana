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

(defn ssdp-parse
  "Parses an incoming SSDP message into a simple AST as defined by the grammar above.
  Returns the original object with the :message key replaced with the AST."
  [channel-msg]
  (let [parse-result (ssdp-parser (:message channel-msg))]
    (when (parser/failure? parse-result)
      (log/debug "Error parsing" (:message channel-msg))
      (throw parse-result))
    (log/spy :trace "SSDP parser out"
             (assoc channel-msg :message parse-result))))

(defn error-handler
  "Logs the supplied error."
  [ex]
  (log/error "Exception parsing msg:\n" ex))

(defn header-map
  "Converts an AST of SSDP headers into a key value map."
  [hdrs-ast]
  (into {} (map #(let [[HEADER [NAME name] [VALUE value]] %]
                   [(keyword (str/lower-case name)) value])
                hdrs-ast)))

(defn set-expiration [ann]
  (let [timestamp (:timestamp ann)
        cache-header (-> ann :message :headers :cache-control)]
    (if cache-header
      (let [age-millis (* 1000 (js/parseInt
                                (second (first (re-seq #"max-age[ ]*=[ ]*([1234567890]*)"
                                                       cache-header)))))]
        (assoc ann :expiration (js/Date. (+ age-millis (.getTime timestamp)))))
      ann)))

(defn ssdp-analyzer
  "Analyzes the AST from the parser, extracting values and converting to a map.
  Returns the supplied object, with the :message key replaced with the new data structure."
  [parse-result]
  (let [[SSDP_MSG [START_LINE [type]] [HEADERS & headers] body] (:message parse-result)]
    (log/spy :trace "SSDP anlzr out"
             (set-expiration
              (assoc parse-result :message {:type type
                                            :headers (header-map headers)
                                            :body body})))))

(defonce ssdp-message-channel (atom nil))
(defonce ssdp-event-channel (atom nil))
(defonce ssdp-publisher (atom nil))

(defn descriptor-parse
  "Analyzes the response received on the descriptor-channel and performs an
  action, depending on the state of the response. If there is no error, it replaces
  the :message XML string with a generic xml-clj parse. Otherwise, the original
  response is pushed through the descriptor publication so approprate action can
  be taken on the application state."
  [channel-msg]
  (try
    (log/spy :trace "Desc parser out"
             (if (:error channel-msg)
               channel-msg ; push the original message with error state through the pipe...
               (assoc channel-msg :message (xml/xml->clj (:message channel-msg)))))
    (catch :default e
      (log/error e "Unexpected error parsing descriptor" channel-msg))))

(defn xml-map
  "Returns a function that converts an xml->clj data structure into a map {:<tag-name> <content>},
  according to the supplied spec. spec is a map from a tag keyword to a function of a single argument.
  When a child node with a tag name appearing in the spec map is found, the corresponding function is
  called with the node as argument. The return value is used as the <content> in the resulting map."
  [spec & {:keys [include-unspec-elt] :or {:include-unspec-elt false}}]
  (fn [node]
    (into {} (reduce (fn [out child]
                       (if-let [spec-fun (spec (:tag child))]
                         (cons [(:tag child) (spec-fun child)] out)
                         (if include-unspec-elt
                           (cons [(:tag child) (xml-util/text child)] out)
                           out)))
                     '()
                     (node :content)))))

(defn xml-list
  "Returns a function that converts an xml-clj data structure into a list, based
  on the supplied 'spec'. The spec is a map of keywords to functions. If the
  tag of the child element is a member of the spec map, the associated function
  is called on the child element."
  [spec]
  (fn [node]
    (reduce (fn [out child]
              (if-let [spec-fun (spec (child :tag))]
                (cons (spec-fun child) out)
                out))
            '()
            (node :content))))

(defn analyze-device-descriptor
  "Performs an analysis on the supplied parsed device descriptor and converts it into
  a form that is easier to use."
  [desc]
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

(defn analyze-service-descriptor
  "Performs an analysis on the supplied parsed service descriptor and converts
  it into a form that is easier to use."
  [desc]
  ((xml-map
    {:specVersion (xml-map
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

(defn analyze-descriptor
  "Performs an analysis on the supplied message from the descriptor
  channel, breaking the parsed xml structures into easier to manage
  pieces. If the error flag is true, we just pass the original
  object through."
  [channel-msg]
  (log/spy :trace "Desc anlzr out"
           (if (:error channel-msg)
             channel-msg ; push the original object through the pipeline.
             (let [desc (:message channel-msg)]
               (assoc channel-msg
                      :message (case (:tag desc)
                                 :root (analyze-device-descriptor desc)
                                 :scpd (analyze-service-descriptor desc)
                                 desc))))))

(defn descriptor-discriminator
  "Given a message from the descriptor channel, this function discriminates
  whether it is a device descriptor or service descriptor."
  [msg]
  (if (:service-info msg)
    :service
    :device))

;; Hack city, next 2 functions. data.xml does not support node-js and
;; tubax does not support xml namespaces, so gotta do this mess:
(defn ns-map [attrs]
  (into {}
        (map (fn [[k v]]
               [(subs (name k) (count "xmlns:")) v])
             (filter (fn [[k v]] (str/starts-with? (name k) "xmlns"))
                          attrs))))

;; Converts namespace qualified tag keywords to arrays, [prefix tag ns-uri].
;; Only for tags, not attributes.
(defn munge-namespaces [xml ns-ctx]
  (if (and (map? xml) (:tag xml))
    (let [ns-cur (merge ns-ctx (ns-map (:attributes xml)))
          tag-split (str/split (name (:tag xml)) ":")
          result (assoc xml :content (map (fn [x] (munge-namespaces x ns-cur))
                                          (:content xml)))]
      (if (> (count tag-split) 1)
        (let [ns-uri (ns-cur (first tag-split))]
          (if ns-uri
            (assoc result :tag [(second tag-split) ns-uri])
            result))
        result))
    xml))

(defn event-parse [msg]
  (try
    (assoc msg :message {:body (xml/xml->clj (-> msg :message :body))
                         :type :NOTIFY
                         :headers (-> msg :message :headers)})
    (catch :default e
      ((:error msg) 400 "Malformed message")
      (throw e))))

(defn event-analyzer [msg]
  (let [m (munge-namespaces (-> msg :message :body) {})]
    (assoc msg :message {:body (apply merge ((xml-list
                                              {["property" "urn:schemas-upnp-org:event-1-0"] (xml-map {} :include-unspec-elt true)})
                                             m))
                         :type :NOTIFY
                         :headers (-> msg :message :headers)})))

(defonce descriptor-channel (atom nil))
(defonce descriptor-publisher (atom nil))

(defn start-ssdp-parser []
  (reset! ssdp-message-channel
          (async/chan 1
                      (comp (map ssdp-parse) (map ssdp-analyzer))
                      error-handler))
  (reset! ssdp-event-channel
          (async/chan 1
                      (comp (map event-parse) (map event-analyzer))
                      error-handler))
  (reset! ssdp-publisher
          (async/pub (async/merge [@ssdp-message-channel @ssdp-event-channel])
                     (comp :type :message)))
  (reset! descriptor-channel
          (async/chan 1
                      (comp (map descriptor-parse) (map analyze-descriptor))
                      error-handler))
  (reset! descriptor-publisher
          (async/pub @descriptor-channel descriptor-discriminator)))

(defn stop-ssdp-parser []
  (async/close! @ssdp-message-channel)
  (async/close! @ssdp-event-channel)
  (async/close! @descriptor-channel))
