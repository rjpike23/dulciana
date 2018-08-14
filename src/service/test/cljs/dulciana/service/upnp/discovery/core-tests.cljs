;  Copyright 2017-2018, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns ^:figwheel-always dulciana.service.upnp.discovery.core-tests
  (:require [cljs.core.async :as async]
            [cljs.test :refer-macros [async deftest is testing run-tests]]
            [taoensso.timbre :as log :include-macros true]
            [events :as node-events]
            [dgram :as node-dgram]
            [dulciana.service.upnp.discovery.core :as discovery]))

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

(defn create-sock-mock [bind-fn add-m-fn send-fn close-fn]
  (let [sock-mock (js/Object.create (node-events/EventEmitter.))]
    (set! (.-bind sock-mock) bind-fn)
    (set! (.-addMembership sock-mock) add-m-fn)
    (set! (.-send sock-mock) send-fn)
    (set! (.-close sock-mock) close-fn)
    sock-mock))

(deftest remove-expired
  (is (= {"uuid:abd::124" *valid-announcement*}
         (discovery/remove-expired-items *announcements*))))

(deftest remove-announcement
  (is (= {}
         (discovery/remove-announcements (atom *announcements*) *valid-announcement*)))
  (is (= {"uuid:abd::124" *valid-announcement*}
         (discovery/remove-announcements (atom *announcements*) *expired-announcement*))))

(deftest start-listener-test
  (let [iface {:family "IPv4" :address "1.2.3.4"}
        calls (atom #{})
        bind-fn (fn [& args]
                  (swap! calls conj :bind)
                  (is (= discovery/*ssdp-port* (first args)))
                  (is (or (= 1 (count args)) (= (:address iface) (second args))))
                  nil)
        add-m-fun (fn [& args]
                    (swap! calls conj :addMembership)
                    nil)
        send-fun (fn [& args]
                   (swap! calls conj :send)
                   nil)
        close-fun (fn [& args]
                    (swap! calls conj :close))
        sock-mock (create-sock-mock bind-fn add-m-fun send-fun close-fun)]
    (with-redefs [node-dgram/createSocket (constantly sock-mock)]
      (let [result (discovery/start-listener [:em0 iface])]
        (.emit sock-mock "listening")
        (.emit sock-mock "message" "hello")
        (.emit sock-mock "close")
        (async done
               (async/go
                 (let [[this msg] (async/<! (:message (:channels result)))]
                   (is (= msg "hello"))
                   (is (.-bind this)))
                 (is (= (:socket result) sock-mock))
                 (is (= #{:listening :error :message :close} (set (keys (:channels result)))))
                 (is (every? identity (vals (:channels result))))
                 (is (= #{:bind :addMembership :send} @calls))
                 (done)))))))

(deftest start-listener-err-test
  (let [iface {:family "IPv4" :address "1.2.3.4"}
        calls (atom #{})
        bind-fn (fn [& args]
                  (swap! calls conj :bind)
                  nil)
        add-m-fun (fn [& args]
                    (swap! calls conj :addMembership)
                    nil)
        send-fun (fn [& args]
                   (swap! calls conj :send)
                   nil)
        close-fun (fn [& args]
                    (swap! calls conj :close)
                    (this-as this
                      (.emit this "close")))
        sock-mock (create-sock-mock bind-fn add-m-fun send-fun close-fun)]
    (with-redefs [node-dgram/createSocket (constantly sock-mock)]
      (let [result (discovery/start-listener [:em0 iface])]
        (.emit sock-mock "error" (js/Error. "Testing"))
        (async done
               (async/go
                 (let [m (async/<! (:message (:channels result)))]
                   (is (= nil m))
                   (is (= #{:bind :close} @calls)))
                 (done)))))))
