;  Copyright 2017-2018, Radcliffe J. Pike. All rights reserved.
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
            [dulciana.service.upnp.description.messages :as msg]
            [dulciana.service.upnp.discovery.core :as discovery]))

(defonce *announcements-sub* (atom nil))

(defonce *remote-devices* (atom {}))

(defonce *remote-devices-pub* (events/wrap-atom *remote-devices*))

(defonce *remote-devices-sub* (atom nil))

(defonce *remote-services* (atom {}))

(defonce *remote-services-pub* (events/wrap-atom *remote-services*))

(defonce *remote-services-sub* (atom nil))

(defonce *local-devices* (atom nil))

(defn find-device [dev-id]
  (some (fn [[k v]] (and (= (discovery/get-dev-id k) dev-id) v))
        @*remote-devices*))

(defn find-all-service-ids-for-device [dev-id]
  (map #(:serviceId %)
       (-> (find-device dev-id) :device :serviceList)))

(defn find-service [dev-id svc-id]
  (let [dev (find-device dev-id)]
    (when dev
      (some #(and (= (:serviceId %) svc-id) %)
            (-> dev :device :serviceList)))))

(defn find-scpd [svc-usn]
  (@*remote-services* svc-usn))

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
  (doseq [[k v] @*remote-services*]
    (when (= (type v) Atom)
      (when (compare-and-set! v :new :pending)
        (async/go
          (when-let [ann (discovery/find-announcement (discovery/get-dev-id k))]
            (when-let [svc (find-service (discovery/get-dev-id k) (discovery/get-svc-id k))]
              (let [c (request-service-descriptor ann svc)
                    result (async/<! c)]
                (swap! *remote-services* assoc k (:message result))))))))))

(defn process-remote-devices-updates [updates]
   (doseq [[k v] @*remote-devices*] ; ignore the updates param, go direct to *r-d*
     (if (= (type v) Atom)
      (when (compare-and-set! v :new :pending)
        (async/go
          (when-let [ann (discovery/find-announcement k)]
            (let [c (request-device-descriptor ann)
                  result (async/<! c)]
              (swap! *remote-devices* assoc k (:message result))))))
      (swap! *remote-services*
             (fn [svcs]
               (merge (into {} (map (fn [s] [(discovery/create-usn k (:serviceId s)) (atom :new)])
                                    (-> v :device :serviceList)))
                      svcs))))))

(defn process-discovery-updates [discovery-updates]
  (swap! *remote-devices*
         (fn [devs]
            (let [deletes (set (map discovery/get-dev-id (:delete discovery-updates)))
                 devs-dels (apply dissoc devs deletes)
                 adds (set/difference (set (map discovery/get-dev-id (keys (:add discovery-updates))))
                                      (set (keys @*remote-devices*)))]
              (into devs-dels (map (fn [k] [k (atom :new)]) adds))))))

(defn start-listeners []
  (reset! *announcements-sub* (async/chan))
  (reset! *remote-devices-sub* (async/chan))
  (reset! *remote-services-sub* (async/chan))
  (async/sub discovery/*announcements-pub* :update @*announcements-sub*)
  (events/channel-driver @*announcements-sub* process-discovery-updates)
  (async/sub *remote-devices-pub* :update @*remote-devices-sub*)
  (events/channel-driver @*remote-devices-sub* process-remote-devices-updates)
  (async/sub *remote-services-pub* :update @*remote-services-sub*)
  (events/channel-driver @*remote-services-sub* process-remote-services-updates))

(defn stop-listeners []
  (async/unsub *remote-services-pub* :update @*remote-services-sub*)
  (when @*remote-services-sub* (async/close! @*remote-services-sub*))
  (async/unsub *remote-devices-pub* :update @*remote-devices-sub*)
  (when @*remote-devices-sub* (async/close! @*remote-devices-sub*))
  (async/unsub discovery/*announcements-pub* :update @*announcements-sub*)
  (when @*announcements-sub* (async/close! @*announcements-sub*)))
