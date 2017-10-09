;;;; Copyright 2017, Radcliffe J. Pike. All rights reserved.

(ns dulciana.service.state
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [clojure.string :as str]
            [clojure.set :as set]
            [dulciana.service.net :as net]
            [dulciana.service.parser :as parser]
            [taoensso.timbre :as log :include-macros true]))

(defn swap-with-effects! [atom transformer side-effector]
  (when (not= @atom (swap! atom transformer))
    (side-effector @atom)))

(defn get-dev-id [usn]
  (first (str/split usn "::")))

(defn get-svc-id [usn]
  (second (str/split usn "::")))

(defn create-usn [dev-id svc-id]
  (if svc-id
    (str dev-id "::" svc-id)
    dev-id))

(defonce announcements (atom {}))

(defonce remote-devices (atom {}))

(defonce remote-services (atom {}))

;;; SHIT THIS IS ALL FUCKED UP.
(defn do-request [dev-id]
  (swap-with-effects! remote-devices
                      (fn [devs] (if (@announcements dev-id)
                                   (assoc devs dev-id :pending)
                                   (dissoc devs dev-id)))
                      (fn [devs] (when-let [announcement (@announcements dev-id)]
                                   (net/get-device-descriptor (@announcements dev-id))))))

(defn announcement-watcher [announcements]
  (let [announced-devs (set (map get-dev-id (keys announcements)))
        fetched-devs (set (keys @remote-devices))
        remove-devs (set/difference fetched-devs announced-devs)
        new-devs (set/difference announced-devs fetched-devs)]
    (log/debug "watcher called")
    (swap-with-effects! remote-devices
                        (fn [devs]
                          (merge (into {} (map (fn [id] [id :new]) new-devs))
                                 (dissoc devs remove-devs)))
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

(defn update-announcements [notification]
  (log/debug "Updating")
  (swap-with-effects! announcements
                      (fn [anns]
                        (assoc (remove-expired-announcements anns)
                               ((-> notification :message :headers) "usn") notification))
                      announcement-watcher))

(defn remove-announcement [notification]
  (log/debug "Removing")
  (swap-with-effects! announcements
                      (fn [anns]
                        (dissoc (remove-expired-announcements anns)
                                ((-> notification :message :headers) "usn")))
                      announcement-watcher))

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
        (log/debug "Notification received")
        (let [notify-type ((-> notification :message :headers) "nts")]
          (case notify-type
            "ssdp:alive" (update-announcements notification)
            "ssdp:update" (update-announcements notification)
            "ssdp:byebye" (remove-announcement notification)
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
        (log/debug "Got dev descriptor")
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
        (log/debug "Got svc descriptor")
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
