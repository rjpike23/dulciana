;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns ^:figwheel-always dulciana.service.tests
  (:require [clojure.string :as str]
            [dulciana.service.core :as core]
            [dulciana.service.parser :as parser]
            [dulciana.service.messages :as msg]
            [dulciana.service.state :as state]
            [cljs.test :refer-macros [deftest is testing run-tests]]))

(def *notify-msg*
  (str/join "\r\n"
            ["NOTIFY * HTTP/1.1"
             "HOST: 239.255.255.250:1900"
             "CACHE-CONTROL: max-age = 50000"
             "LOCATION: http://example.com/desc.xml"
             "NT: upnp:rootdevice"
             "NTS: ssdp:alive"
             "SERVER: OS/1.0 UPnP/2.0 product/1.0"
             "USN: uuid:00000000-0000-0000-0000-000000000000\r\n\r\n"]))

(def *search-msg*
  (str/join "\r\n"
            ["M-SEARCH * HTTP/1.1"
             "HOST: 239.255.255.250:1900"
             "MAN: \"ssdp:discover\""
             "ST: sddp:all"
             "USER-AGENT: OS/1.0 UPnP/2.0 product/1.0\r\n\r\n"]))

(def *response-msg*
  (str/join "\r\n"
            ["HTTP/1.1 200 OK"
             "CACHE-CONTROL: max-age = 50000"
             "DATE: Fri, 26 Jan 2018 02:47:31 GMT"
             "EXT: "
             "LOCATION: http://example.com/desc.xml"
             "SERVER: OS/1.0 UPnP/2.0 product/1.0"
             "ST: ssdp:all"
             "USN: uuid:00000000-0000-0000-0000-000000000000\r\n\r\n"]))

(def *accept-malformed-msg*
  (str/join "\r\n"
            ["HTTP/1.1 200 OK"
             "A-HEADER: value"]))

(def *illegal-msg* "I am not a valid SSDP message")

(def *expired-announcement*
  {:remote
   {:address "127.0.0.1" :family "IPv4" :port 1900 :size 1000}
   :interface
   {:address "127.0.0.1" :netmask "255.255.255.255" :family "IPv4" :mac "00:00:00:00:00:00" :internal true}
   :message
   {:type :NOTIFY
    :headers
    {:host "239.255.255.250:1900"
     :cache-control "max-age=60"
     :location "http://example.com/desc.xml"
     :nts "ssdp:alive"
     :server "POSIX, UPnP/1.0 UPnP Stack/6.37.14.62"
     :nt "uuid:abc::123"
     :usn "uuid:abc::123"}
    :body nil}
   :expiration (js/Date. 0)})

(def *valid-announcement*
  {:remote
   {:address "127.0.0.1" :family "IPv4" :port 1900 :size 1000}
   :interface
   {:address "127.0.0.1" :netmask "255.255.255.255" :family "IPv4" :mac "00:00:00:00:00:00" :internal true}
   :message
   {:type :NOTIFY
    :headers
    {:host "239.255.255.250:1900"
     :cache-control "max-age=60"
     :location "http://example.com/desc.xml"
     :nts "ssdp:alive"
     :server "POSIX, UPnP/1.0 UPnP Stack/6.37.14.62"
     :nt "uuid:abd::124"
     :usn "uuid:abd::124"}
    :body nil}
   :expiration (js/Date. 10000000000000)})

(def *announcements*
  {"uuid:abc::123" *expired-announcement*
   "uuid:abd::124" *valid-announcement*})

;; The format of the parser output is subject to change with the grammar, thus,
;; there are no assertions on the content of the return value.
(deftest test-parse
  (is (not (nil? (parser/ssdp-parse {:message *notify-msg*}))))
  (is (not (nil? (parser/ssdp-parse {:message *search-msg*}))))
  (is (not (nil? (parser/ssdp-parse {:message *response-msg*}))))
  (is (not (nil? (parser/ssdp-parse {:message *accept-malformed-msg*}))))
  (try
    (parser/ssdp-parse {:message *illegal-msg*})
    (is nil "Expected error to be thrown.")
    (catch js/Error e nil)))

(deftest test-analyze
  (let [notify-result (parser/ssdp-analyzer (parser/ssdp-parse {:message *notify-msg*
                                                                :timestamp (js/Date. 0)}))]
    (is (= :NOTIFY (-> notify-result :message :type)))
    (is (= "239.255.255.250:1900" (-> notify-result :message :headers :host)))))

(deftest remove-expired
  (is (= {"uuid:abd::124" *valid-announcement*}
         (state/remove-expired-announcements *announcements*))))

(deftest remove-announcement
  (is (= {}
         (state/remove-announcements (atom *announcements*) *valid-announcement* (constantly nil))))
  (is (= {"uuid:abd::124" *valid-announcement*}
         (state/remove-announcements (atom *announcements*) *expired-announcement* (constantly nil)))))

(run-tests)
