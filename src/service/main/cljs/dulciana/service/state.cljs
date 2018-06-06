;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.state
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [clojure.string :as str]
            [clojure.set :as set]
            [dulciana.service.net :as net]
            [dulciana.service.parser :as parser]
            [dulciana.service.events :as events]
            [taoensso.timbre :as log :include-macros true]))

#_(defn swap-with-effects!
  "This function performs a swap! and conditionally executes a
  function which may have side effects, if the call to swap!
  actually changes the referenced atom. Note: Investigate need
  for this fn."
  [atom transformer side-effector]
  (let [prev @atom
        result (swap! atom transformer)]
    (when (not= prev result)
      (side-effector result))
    result))

#_(defn create-usn
  "Constructs a USN from the device id and service id."
  [dev-id svc-id]
  (if svc-id
    (str dev-id "::" svc-id)
    dev-id))

#_(defn get-announced-device-ids [announcement-map]
  (set (map (fn [[k v]] (get-dev-id k)) announcement-map)))

#_(defn get-announced-services-for-device [dev-id announcement-map]
  (set (map (fn [[k v]] k) (filter (fn [[k v]] (str/starts-with? k dev-id)) announcement-map))))



(defonce remote-devices (atom {}))

(defonce remote-services (atom {}))

(defonce local-services (atom {}))

(defonce local-devices (atom {}))

(defonce subscriptions (atom {}))

#_(defn find-announcement [dev-id]
  (some (fn [[k v]] (when (str/starts-with? k dev-id) v))
        @announcements))

#_(defn find-device [dev-id]
  (@remote-devices dev-id))

#_(defn find-service [dev-id svc-id]
  (let [dev (find-device dev-id)]
    (when dev
      (some #(when (= (:serviceId %) svc-id) %)
            (-> dev :device :serviceList)))))

#_(defn find-scpd [svc-usn]
  (@remote-services svc-usn))

#_(defn submit-dev-desc-request [dev-id]
  (swap-with-effects! remote-devices
                      (fn [devs] (if (@announcements dev-id)
                                      (assoc devs dev-id :pending)
                                      (dissoc devs dev-id)))
                      (fn [devs] (when-let [announcement (@announcements dev-id)]
                                   (net/get-device-descriptor announcement)))))

#_(defn sync-devices [announcements]
  (let [announced-devs (get-announced-device-ids announcements)
        fetched-devs (set (keys @remote-devices))
        remove-devs (set/difference fetched-devs announced-devs)
        new-devs (set/difference announced-devs fetched-devs)]
    (swap-with-effects! remote-devices
                        (fn [devs]
                          (merge (into {} (map (fn [id] [id :new]) new-devs))
                                 (apply dissoc devs remove-devs)))
                        (fn [devs]
                          (doseq [[dev-id _] (filter (fn [[k v]] (= v :new)) devs)]
                            (submit-dev-desc-request dev-id))))))

#_(defn update-subscription-state [event]
  (let [sid (-> event :message :headers :sid)]
    (if (@subscriptions sid)
      (do
        ; TODO: dispatch event to service specific handler.
        (when (:ok event)
          ((:ok event))))
      (do
        (log/warn "Received event with unknown SID" sid)
        (when (:error event)
          ((:error event) 412 "Invalid SID"))))))

#_(defn update-subscriptions [sub-atom sub]
  (swap! sub-atom
         (fn [subs]
           (assoc (remove-expired-items subs) (:sid sub) sub))))

#_(defn remove-subscriptions [sub-atom & subs]
  (let [sid-set (set (map :sid subs))]
    (swap! sub-atom (fn [subs-map]
                      (into {} (filter #(not (contains? sid-set (first %)))
                                       subs-map))))))

(defonce sessions (atom {}))


(defonce notify-channel (atom nil))


(defonce search-channel (atom nil))

(defn process-search [search]
  (log/trace "Search received" search))

(defonce response-channel (atom nil))

#_(defn process-response [response]
  (update-announcements announcements response sync-devices))

(defonce device-descriptor-channel (atom nil))

#_(defn process-device-descriptor [dev-desc]
  (if (:error dev-desc)
    (do
      (log/error "Error retrieving dev desc:" dev-desc)
      (remove-announcements announcements (:announcement dev-desc) sync-devices))
    (do
      (log/debug "Got dev desc:" (-> dev-desc :message :device :UDN))
      (swap-with-effects! remote-devices
                          (fn [devs]
                            (assoc devs (-> dev-desc :message :device :UDN) (:message dev-desc)))
                          (fn [devs]
                            (doseq [svc-desc (-> dev-desc :message :device :serviceList)]
                              (net/get-service-descriptor (:announcement dev-desc) svc-desc)))))))

(defonce service-descriptor-channel (atom nil))

#_(defn process-service-descriptor [svc-desc]
  (if (:error svc-desc)
    (log/error "Error while accessing service descriptor:" (:message svc-desc))
    (do
      (log/debug "Got svc desc" (-> svc-desc :service-info :serviceId))
      (swap! remote-services
             (fn [svcs]
               (assoc svcs (create-usn
                            (get-dev-id (-> svc-desc :announcement :message :headers :usn))
                            (-> svc-desc :service-info :serviceId))
                      (svc-desc :message)))))))

#_(defn start-subscribers []
  (reset! notify-channel (async/chan))
  (reset! search-channel (async/chan))
  (reset! response-channel (async/chan))
  (reset! device-descriptor-channel (async/chan))
  (reset! service-descriptor-channel (async/chan))
  (async/sub @parser/ssdp-publisher :NOTIFY @notify-channel)
  (async/sub @parser/ssdp-publisher :SEARCH @search-channel)
  (async/sub @parser/ssdp-publisher :RESPONSE @response-channel)
  (async/sub @parser/descriptor-publisher :device @device-descriptor-channel)
  (async/sub @parser/descriptor-publisher :service @service-descriptor-channel)
  ;(events/channel-driver @notify-channel process-notification)
  (events/channel-driver @search-channel process-search)
  (events/channel-driver @response-channel process-response)
  (events/channel-driver @device-descriptor-channel process-device-descriptor)
  (events/channel-driver @service-descriptor-channel process-service-descriptor))

(defn stop-subscribers [])
