;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns ^:figwheel-always dulciana.service.upnp.discovery.messages-tests
  (:require [cljs.core.async :as async]
            [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is testing run-tests]]
            [taoensso.timbre :as log :include-macros true]
            [dulciana.service.upnp.discovery.messages :as messages]))

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

;; The format of the parser output is subject to change with the grammar, thus,
;; there are no assertions on the content of the return value.
(deftest test-parse
  (is (not (nil? (messages/ssdp-parse {:message *notify-msg*}))))
  (is (not (nil? (messages/ssdp-parse {:message *search-msg*}))))
  (is (not (nil? (messages/ssdp-parse {:message *response-msg*}))))
  (is (not (nil? (messages/ssdp-parse {:message *accept-malformed-msg*}))))
  (is (:error (messages/ssdp-parse {:message *illegal-msg*}))))

(deftest test-analyze
  (let [notify-result (messages/ssdp-analyzer (messages/ssdp-parse {:message *notify-msg*
                                                                    :timestamp (js/Date. 0)}))]
    (is (= :NOTIFY (:type notify-result)))
    (is (= "239.255.255.250:1900" (-> notify-result :message :headers :host)))
    (is (:expiration notify-result))))

(deftest test-analyze-error
  (let [err-result (messages/ssdp-analyzer (messages/ssdp-parse {:message *illegal-msg*}))]
    (is (= :error (:type err-result)))))
