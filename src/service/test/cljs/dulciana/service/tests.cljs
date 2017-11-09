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

(deftest test-parse
  (is (not (nil? (parser/ssdp-parse {:message *notify-msg*}))))
  (is (not (nil? (parser/ssdp-parse {:message *search-msg*})))))

(run-tests)
