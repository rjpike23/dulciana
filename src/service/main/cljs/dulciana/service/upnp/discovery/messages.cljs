;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.discovery.messages
  (:require [clojure.string :as str]
            [instaparse.core :as parser :refer-macros [defparser]]
            [taoensso.timbre :as log :include-macros true]))

(defn emit-ssdp-request-msg
  "Formats an SSDP request message using the supplied verb/method, address
  and map of headers."
  [verb address headers]
  (str (str/join "\r\n"
                 (cons (str verb " " address " HTTP/1.1")
                       (map (fn [[k v]] (str (str/upper-case (name k)) ": " v)) headers)))
       "\r\n\r\n"))

(defn emit-ssdp-response-msg
  [headers]
  (str (str/join "\r\n"
                 (cons "HTTP/1.1 200 OK"
                       (map (fn [[k v]] (str (str/upper-case (name k)) ": " v)) headers)))))

(defn emit-m-search-msg
  "Constructs a M-SEARCH SSDP discovery message. Returns a string."
  []
  (emit-ssdp-request-msg "M-SEARCH" "*"
                         {:host "239.255.255.250:1900"
                          :man "\"ssdp:discover\""
                          :mx 5
                          :st "ssdp:all"}))

(defn emit-notify-msg [scdp-location nts notify-type usn]
  (emit-ssdp-request-msg "NOTIFY" "*"
                         {:host "239.255.255.250:1900"
                          :cache-control "max-age=1800"
                          :location scdp-location
                          :nt notify-type
                          :nts nts
                          :usn usn}))

(defn emit-m-search-response-msg [scdp-location server st usn]
  (emit-ssdp-response-msg {:date (.toUTCString (js/Date.))
                           :ext ""
                           :location scdp-location
                           :server server
                           :st st
                           :usn usn}))

(defn emit-device-goodbye [notify-type usn]
  (emit-ssdp-request-msg "NOTIFY" "*"
                         {:host "239.255.255.250:1900"
                          :nt notify-type
                          :nts "ssdp:byebye"
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
    (if (parser/failure? parse-result)
      (assoc channel-msg :error parse-result)
      (log/spy :trace "SSDP parser out"
               (assoc channel-msg :message parse-result)))))

(defn set-expiration [ann]
  (let [timestamp (:timestamp ann)
        cache-header (-> ann :message :headers :cache-control)]
    (if cache-header
      (let [age-millis (* 1000 (js/parseInt
                                (second (first (re-seq #"max-age[ ]*=[ ]*([1234567890]*)"
                                                       cache-header)))))]
        (assoc ann :expiration (js/Date. (+ age-millis (.getTime timestamp)))))
      ann)))

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

(defn ssdp-analyzer
  "Analyzes the AST from the parser, extracting values and converting to a map.
  Returns the supplied object, with the :message key replaced with the new data structure."
  [parse-result]
  (if (:error parse-result)
    (assoc parse-result :type :error)
    (let [[SSDP_MSG [START_LINE [type]] [HEADERS & headers] body] (:message parse-result)]
      (log/spy :trace "SSDP anlzr out"
               (set-expiration
                (assoc parse-result
                       :type type
                       :message {:headers (header-map headers)
                                 :body body}))))))
