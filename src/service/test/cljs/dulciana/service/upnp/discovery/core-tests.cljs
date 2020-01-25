;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
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
            [dulciana.service.config :as config]
            [dulciana.service.store :as store]
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

(defn create-sock-mock [bind-fn add-m-fn send-fn close-fn address-fn]
  (let [sock-mock (js/Object.create (node-events/EventEmitter.))]
    (set! (.-bind sock-mock) bind-fn)
    (set! (.-addMembership sock-mock) add-m-fn)
    (set! (.-send sock-mock) send-fn)
    (set! (.-close sock-mock) close-fn)
    (set! (.-address sock-mock) address-fn)
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
  (let [iface {:family "IPv4" :address "1.2.3.4" :port 123}
        calls (atom #{})
        bind-fn (fn [& args]
                  (swap! calls conj :bind)
                  (is (= (:ssdp-mcast-port @config/*config*) (first args)))
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
        address-fn (fn [& args]
                     (swap! calls conj :address)
                     iface)
        sock-mock (create-sock-mock bind-fn add-m-fun send-fun close-fun address-fn)]
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
                 (is (= #{:bind :addMembership :send :address} @calls))
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
        address-fn (fn [& args]
                     (swap! calls conj :address)
                     iface)
        sock-mock (create-sock-mock bind-fn add-m-fun send-fun close-fun address-fn)]
    (with-redefs [node-dgram/createSocket (constantly sock-mock)]
      (let [result (discovery/start-listener [:em0 iface])]
        (.emit sock-mock "error" (js/Error. "Testing"))
        (async done
               (async/go
                 (let [m (async/<! (:message (:channels result)))]
                   (is (= nil m))
                   (is (= #{:bind :close} @calls)))
                 (done)))))))

(deftest create-root-device-announcements-test
  (let [dd (store/map->device
            {:udn "uuid:test-device-uuid"
             :device-type "test-device-type"
             :version "1.0"
             :device-list (list (store/map->device {:udn "uuid:test-embedded-device-uuid"
                                                          :device-type "test-embedded-device-type"
                                                          :version "2.0"
                                                          :service-list (list (store/map->service {:service-type "test-service-type-0"}))}))
             :service-list (list (store/map->service {:service-type "test-service-type-1"})
                                 (store/map->service {:service-type "test-service-type-2"}))})
        ann (discovery/create-root-device-announcements :notification-type dd)]
    (is (= 8 (count ann)))
    (is (every? #(= (:type %) :notification-type) ann))
    (is (some #(= (:nt %) "upnp:rootdevice") ann))
    (is (some #(= (:nt %) "uuid:test-device-uuid") ann))
    (is (some #(= (:nt %) "test-device-type:1.0") ann))
    (is (some #(= (:nt %) "uuid:test-embedded-device-uuid") ann))
    (is (some #(= (:nt %) "test-embedded-device-type:2.0") ann))
    (is (some #(= (:nt %) "test-service-type-0") ann))
    (is (some #(= (:nt %) "test-service-type-1") ann))
    (is (some #(= (:nt %) "test-service-type-2") ann))
    (is (some #(= (:usn %) "uuid:test-device-uuid::upnp:rootdevice") ann))
    (is (some #(= (:usn %) "uuid:test-device-uuid") ann))
    (is (some #(= (:usn %) "uuid:test-device-uuid::test-device-type:1.0") ann))
    (is (some #(= (:usn %) "uuid:test-embedded-device-uuid") ann))
    (is (some #(= (:usn %) "uuid:test-embedded-device-uuid::test-embedded-device-type:2.0") ann))
    (is (some #(= (:usn %) "uuid:test-embedded-device-uuid::test-service-type-0") ann))
    (is (some #(= (:usn %) "uuid:test-device-uuid::test-service-type-1") ann))
    (is (some #(= (:usn %) "uuid:test-device-uuid::test-service-type-2") ann))
    (is (every? #(= (:location %) "/upnp/devices/uuid:test-device-uuid/devDesc.xml") ann))))

(deftest create-root-device-announcements-nil-test
  (let [ann (discovery/create-root-device-announcements :notify nil)]
    (is (nil? ann))))
