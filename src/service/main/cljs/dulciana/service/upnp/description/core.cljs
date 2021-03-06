;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.description.core
  (:require [cljs.core.async :as async]
            [clojure.set :as set]
            [taoensso.timbre :as log :include-macros true]
            [url :as url]
            [dulciana.service.events :as events]
            [dulciana.service.net :as net]
            [dulciana.service.store :as store]
            [dulciana.service.upnp.description.messages :as msg]
            [dulciana.service.upnp.discovery.core :as discovery]))

(defonce +announcements-sub+ (atom nil))

(defonce +remote-devices-sub+ (atom nil))

(defonce +remote-services-sub+ (atom nil))

(defn get-control-url [svc-usn]
  (let [ann (@store/+announcements+ (store/get-dev-id svc-usn))
        svc (store/find-service (store/get-dev-id svc-usn) (store/get-svc-type svc-usn))]
     (url/resolve (-> ann :message :headers :location) (:controlURL svc))))

(defn get-event-url [svc-usn]
  (let [ann (@store/+announcements+ (store/get-dev-id svc-usn))
        scpd (store/find-scpd svc-usn)]
    (url/resolve (-> ann :message :headers :location) (:eventSubURL scpd))))

;;; HTTP handlers for descriptors:
(defn handle-dev-desc-request [req res]
  (let [dd (store/find-local-device (.-devid (.-params req)))]
    (if dd
      (do
        (. res (type "application/xml"))
        (. res (send (msg/emit-device-descriptor (store/get-descriptor dd)))))
      (. res (sendStatus 404 )))))

(defn handle-scpd-request [req res]
  (let [svc (store/find-local-service (.-usn (.-params req)))]
    (if svc
      (do
        (. res (type "application/xml"))
        (. res (send (msg/emit-scpd svc))))
      (. res (sendStatus 404)))))

;;; HTTP methods for sending requests for descriptors / SOAP requests below:
(defn request-device-descriptor
  "Sends HTTP request to get the descriptor for the device specified in the
  supplied announcement. Returns a channel with the processed result."
  [dev-ann]
  (log/trace "Fetching dev desc" dev-ann)
  (async/pipe (net/send-http-request "GET" (-> dev-ann :message :headers :location) {}
                                     {:announcement dev-ann})
              (async/chan 1 (comp
                             (map msg/descriptor-parse)
                             (map msg/analyze-descriptor)))))

(defn request-service-descriptor
  "Sends HTTP request to get the descriptor for the service specified in the
  supplied device-announcement and service-info objects. Returns a channel
  with the processed result."
  [dev-ann service-info]
  (log/trace "Fetching svc desc" service-info)
  (let [scpdurl (url/resolve (-> dev-ann :message :headers :location) (service-info :SCPDURL))]
    (async/pipe (net/send-http-request "GET" scpdurl {} {:announcement dev-ann :svc service-info})
                (async/chan 1 (comp
                               (map msg/descriptor-parse)
                               (map msg/analyze-descriptor))))))

(defn process-remote-services-updates [updates]
  (doseq [[k v] @store/+remote-services+]
    (when (= (type v) Atom)
      (when (compare-and-set! v :new :pending)
        (async/go
          (when-let [ann (store/find-announcement (store/get-dev-id k))]
            (when-let [svc (store/find-service (store/get-dev-id k) (store/get-svc-type k))]
              (let [c (request-service-descriptor ann svc)
                    result (async/<! c)]
                (swap! store/+remote-services+ assoc k (:message result))))))))))

(defn process-remote-devices-updates [updates]
   (doseq [[k v] @store/+remote-devices+] ; ignore the updates param, go direct to +r-d+
     (if (= (type v) Atom)
      (when (compare-and-set! v :new :pending)
        (async/go
          (when-let [ann (store/find-announcement k)]
            (let [c (request-device-descriptor ann)
                  result (async/<! c)]
              (swap! store/+remote-devices+ assoc k (:message result))))))
      (swap! store/+remote-services+
             (fn [svcs]
               (merge (into {} (map (fn [s] [(store/create-usn k (:serviceId s)) (atom :new)])
                                    (-> v :device :serviceList)))
                      svcs))))))

(defn process-discovery-updates [discovery-updates]
  (swap! store/+remote-devices+
         (fn [devs]
            (let [deletes (set (map store/get-dev-id (:delete discovery-updates)))
                  devs-dels (apply dissoc devs deletes)
                  adds (set/difference (set (map store/get-dev-id (keys (:add discovery-updates))))
                                       (set (keys @store/+remote-devices+)))]
              (into devs-dels (map (fn [k] [k (atom :new)]) adds))))))

(defn start-listeners []
  (reset! +announcements-sub+ (async/chan))
  (reset! +remote-devices-sub+ (async/chan))
  (reset! +remote-services-sub+ (async/chan))
  (async/sub store/+announcements-pub+ :update @+announcements-sub+)
  (events/channel-driver @+announcements-sub+ process-discovery-updates)
  (async/sub store/+remote-devices-pub+ :update @+remote-devices-sub+)
  (events/channel-driver @+remote-devices-sub+ process-remote-devices-updates)
  (async/sub store/+remote-services-pub+ :update @+remote-services-sub+)
  (events/channel-driver @+remote-services-sub+ process-remote-services-updates))

(defn stop-listeners []
  (async/unsub store/+remote-services-pub+ :update @+remote-services-sub+)
  (when @+remote-services-sub+ (async/close! @+remote-services-sub+))
  (async/unsub store/+remote-devices-pub+ :update @+remote-devices-sub+)
  (when @+remote-devices-sub+ (async/close! @+remote-devices-sub+))
  (async/unsub store/+announcements-pub+ :update @+announcements-sub+)
  (when @+announcements-sub+ (async/close! @+announcements-sub+)))
