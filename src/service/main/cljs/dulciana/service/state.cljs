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
            [taoensso.timbre :as log :include-macros true]))

(defn swap-with-effects! [atom transformer side-effector]
  (let [prev @atom
        result (swap! atom transformer)]
    (when (not= prev result)
      (side-effector result))
    result))

(defn get-dev-id [usn]
  (first (str/split usn "::")))

(defn get-svc-id [usn]
  (second (str/split usn "::")))

(defn device-member? [devid svcname]
  (str/starts-with? svcname devid))

(defn create-usn [dev-id svc-id]
  (if svc-id
    (str dev-id "::" svc-id)
    dev-id))

(defonce announcements (atom {}))

(defonce remote-devices (atom {}))

(defonce remote-services (atom {}))

(defn do-request [dev-id]
  (swap-with-effects! remote-devices
                      (fn [devs] (log/debug "*** at do-request" devs) (if (@announcements dev-id)
                                      (assoc devs dev-id :pending)
                                      (dissoc devs dev-id)))
                      (fn [devs] (when-let [announcement (@announcements dev-id)]
                                   (net/get-device-descriptor (@announcements dev-id))))))

(defn announcement-watcher [announcements]
  (let [announced-devs (set (map get-dev-id (keys announcements)))
        fetched-devs (set (keys @remote-devices))
        remove-devs (set/difference fetched-devs announced-devs)
        new-devs (set/difference announced-devs fetched-devs)]
    (swap-with-effects! remote-devices
                        (fn [devs]
                          (merge (into {} (map (fn [id] [id :new]) new-devs))
                                 (apply dissoc devs remove-devs)))
                        (fn [devs]
                          (doseq [[dev-id _] (filter (fn [[k v]] (= v :new)) devs)]
                            (do-request dev-id))))))

(defn expiration [ann]
  (let [timestamp (-> ann :timestamp)
        cache-header ((-> ann :message :headers) "cache-control")
        age-millis (* 1000 (js/parseInt (subs cache-header (count "max-age="))))]
    (js/Date. (+ age-millis (.getTime timestamp)))))

(defn expired? [now [key ann]]
  (< (expiration ann) now))

(defn remove-expired-announcements [anns]
  (into {} (filter (comp not (partial expired? (js/Date.))) anns)))

(defn update-announcements [announcements-atom notification]
  (log/debug "Updating" ((-> notification :message :headers) "usn"))
  (swap-with-effects! announcements-atom
                      (fn [anns]
                        (assoc (remove-expired-announcements anns)
                               ((-> notification :message :headers) "usn") notification))
                      announcement-watcher))

(defn remove-announcements [announcements-atom notification]
  (let [id (get-dev-id ((-> notification :message :headers) "usn"))]
    (log/debug "Removing" id)
    (swap-with-effects! announcements-atom
                        (fn [anns]
                          (into {} (filter (fn [[k v]] (not (device-member? id k)))
                                           (remove-expired-announcements anns))))
                        announcement-watcher)))

(defn get-announced-device-ids [announcement-map]
  (set (map (fn [[k v]] (get-dev-id k)) announcement-map)))

(defn get-announced-services-for-device [dev-id announcement-map]
  (set (map (fn [[k v]] k) (filter (fn [[k v]] (str/starts-with? k dev-id)) announcement-map))))

(defonce sessions (atom {}))

(defonce notify-channel (atom nil))

(defn process-notifications []
  (go-loop []
    (let [notification (async/<! @notify-channel)]
      (when notification
        (let [notify-type ((-> notification :message :headers) "nts")]
          (case notify-type
            "ssdp:alive" (update-announcements announcements notification)
            "ssdp:update" (update-announcements announcements notification)
            "ssdp:byebye" (remove-announcements announcements notification)
            (log/debug "Ignoring announcement type" notify-type)))
        (recur)))))

(defonce search-channel (atom nil))

(defn process-searches []
  (go-loop []
    (let [search (async/<! @search-channel)]
      (when search
        ;(log/debug "Search received")
        (recur)))))

(defonce device-descriptor-channel (atom nil))

(defn process-device-descriptors []
  (go-loop []
    (let [dev-desc (async/<! @device-descriptor-channel)]
      (when dev-desc
        (log/debug "Got dev descriptor" (-> dev-desc :message :device :UDN))
        (swap-with-effects! remote-devices
                            (fn [devs] (assoc devs (-> dev-desc :message :device :UDN) (dev-desc :message)))
                            (fn [devs]
                              (doseq [svc-desc (-> dev-desc :message :device :serviceList)]
                                (net/get-service-descriptor (dev-desc :announcement) svc-desc))))
        (recur)))))

(defonce service-descriptor-channel (atom nil))

(defn process-service-descriptors []
  (go-loop []
    (let [svc-desc (async/<! @service-descriptor-channel)]
      (when svc-desc
        (log/debug "Got svc descriptor" (-> svc-desc :service-info :serviceId))
        (swap! remote-services
               (fn [svcs] (assoc svcs (str (get-dev-id ((-> svc-desc :announcement :message :headers) "usn"))
                                           "::"
                                           (-> svc-desc :service-info :serviceId))
                                 (svc-desc :message))))
        (recur)))))

(defn start-subscribers []
  (reset! notify-channel (async/chan))
  (reset! search-channel (async/chan))
  (reset! device-descriptor-channel (async/chan))
  (reset! service-descriptor-channel (async/chan))
  (async/sub @parser/ssdp-publisher :NOTIFY @notify-channel)
  (async/sub @parser/ssdp-publisher :SEARCH @search-channel)
  (async/sub @parser/descriptor-publisher :device @device-descriptor-channel)
  (async/sub @parser/descriptor-publisher :service @service-descriptor-channel)
  (process-notifications)
  (process-searches)
  (process-device-descriptors)
  (process-service-descriptors))

(defn stop-subscribers [])
