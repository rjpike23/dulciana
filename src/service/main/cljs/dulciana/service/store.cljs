;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.store
  (:require [clojure.string :as str]
            [taoensso.timbre :as log :include-macros true]
            [dulciana.service.config :as config]
            [dulciana.service.events :as events]))

(defrecord argument
    [direction
     name
     retval
     related-state-variable])

(defrecord action
    [argument-list
     name])

(defrecord allowed-value-range
    [maximum
     minimum
     step])

(defrecord service-state-variable
    [allowed-value-list
     allowed-value-range
     data-type
     default-value
     multicast
     name
     send-events])

(defrecord service
    [action-list
     service-id
     service-state-table
     service-type])

(defrecord icon
    [mime-type
     depth
     height
     width
     url])

(defrecord device
    [boot-id
     config-id
     device-list
     device-type
     friendly-name
     icon-list
     manufacturer
     manufacturer-url
     model-description
     model-name
     model-url
     presentation-url
     serial-number
     service-list
     udn
     upc
     version])

(defrecord subscription
    [callback
     sid
     statevar
     timestamp
     timeout
     usn])

(defprotocol upnp-service
  (get-descriptor [this])
  (get-state-atom [this])
  (invoke-action [this action-name args]))

(defonce +external-subscriptions+ (atom {}))

(defonce +internal-subscriptions+ (atom {}))

;;; A map of all received announcements.
(defonce +announcements+ (atom {}))

;;; A core.async/pub of updates to the +announcements+ atom.
(defonce +announcements-pub+ (events/wrap-atom +announcements+))

(defonce +remote-devices+ (atom {}))

(defonce +remote-devices-pub+ (events/wrap-atom +remote-devices+))

(defonce +remote-services+ (atom {}))

(defonce +remote-services-pub+ (events/wrap-atom +remote-services+))

(defonce +local-devices+ (atom (config/get-value :dulciana-init-local-devices)))

(defn create-usn
  "Constructs a USN from the device id and service id."
  [dev-id svc-id]
  (if svc-id
    (str dev-id "::" svc-id)
    dev-id))

(defn get-dev-id
  "Utility function to extract the device id from a USN value."
  [usn]
  (first (str/split usn "::")))

(defn get-svc-type
  "Utility function extract the service id from a USN value."
  [usn]
  (second (str/split usn "::")))

(defn get-uuid
  "Utility function which extracts the UUID from the UDN."
  [udn]
  (second (str/split udn ":")))

(defn find-announcement [dev-id]
  (some (fn [[k v]] (when (str/starts-with? k dev-id) v))
        @+announcements+))

(defn find-device [dev-id]
  (some (fn [[k v]] (and (= (get-dev-id k) dev-id) v))
        @+remote-devices+))

(defn find-service [dev-id svc-id]
  (let [dev (find-device dev-id)]
    (when dev
      (some #(and (= (:serviceId %) svc-id) %)
            (-> dev :device :serviceList)))))

(defn find-all-service-ids-for-device [dev-id]
  (map #(:serviceId %)
       (-> (find-device dev-id) :device :serviceList)))

(defn find-scpd [svc-usn]
  (@+remote-services+ svc-usn))

(defn find-local-device [devid]
  (@+local-devices+ devid))

(defn find-local-service
  ([usn]
   (find-local-service (get-dev-id usn) (get-svc-type usn)))
  ([devid svc-type]
   (let [dev (find-local-device devid)]
     (when dev
       (let [svcs (:service-list dev)]
         (some (fn [svc] (and (= (:service-type svc) svc-type) svc))
               svcs))))))

(defn create-subscription [usn callback statevar timestamp timeout]
  (->subscription callback
                  (str "uuid:" (random-uuid))
                  statevar
                  timestamp
                  timeout
                  usn))
