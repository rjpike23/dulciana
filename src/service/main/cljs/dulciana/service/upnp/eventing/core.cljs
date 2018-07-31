;  Copyright 2017-2018, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.eventing.core
  (:require [cljs.core.async :as async]
            [taoensso.timbre :as log :include-macros true]
            [dulciana.service.events :as events]
            [dulciana.service.net :as net]
            [dulciana.service.upnp.discovery.core :as discovery]
            [dulciana.service.upnp.description.core :as description]))

(defonce *subscriptions* (atom {}))

(defn update-subscription-state [event]
  (let [sid (-> event :message :headers :sid)]
    (if (@*subscriptions* sid)
      (do
        ; TODO: dispatch event to service specific handler.
        (when (:ok event)
          ((:ok event))))
      (do
        (log/warn "Received event with unknown SID" sid)
        (when (:error event)
          ((:error event) 412 "Invalid SID"))))))

(defn update-subscriptions [sub-atom sub]
  (swap! sub-atom
         (fn [subs]
           (assoc (discovery/remove-expired-items subs) (:sid sub) sub))))

(defn remove-subscriptions [sub-atom & subs]
  (let [sid-set (set (map :sid subs))]
    (swap! sub-atom (fn [subs-map]
                      (into {} (filter #(not (contains? sid-set (first %)))
                                       subs-map))))))
(declare resubscribe)

(defn handle-subscribe-response [msg device-id service-id]
  (if (= (-> msg :message :status-code) 200)
    (let [sid (-> msg :message :headers :sid)
          delta-ts (js/parseInt (second (re-matches #"Second-(\d*)"
                                                    (-> msg :message :headers :timeout))))]
      (update-subscriptions *subscriptions*
                                  {:sid sid
                                   :expiration (js/Date. (+ (* 1000 delta-ts) (.getTime (js/Date.))))
                                   :dev-id device-id
                                   :svc-id service-id})
      (async/go
        (async/<! (async/timeout (* 1000 (- delta-ts 60)))) ; resub 60s before expiration.
        (when (@*subscriptions* sid) ; make sure still subbed.
          (resubscribe sid))))
    (log/warn "Error (" (-> msg :message :status-code) ":" (-> msg :message :status-msg)
              ") received from subscribe request, dev=" device-id "svc= " service-id)))

(defn subscribe
  ([device-id service-id]
   (subscribe device-id service-id []))
  ([device-id service-id state-var-list]
   (let [svc (description/find-service device-id service-id)
         ann (discovery/find-announcement device-id)]
     (when (and svc ann)
       (let [c (net/send-subscribe-message ann svc)]
         (async/go
           (let [msg (async/<! c)]
             (handle-subscribe-response msg device-id service-id))))))))

(defn resubscribe [sub-id]
  (let [sub (@*subscriptions* sub-id)
        svc (description/find-service (:dev-id sub) (:svc-id sub))
        ann (discovery/find-announcement (:dev-id sub))]
    (when (and svc ann)
      (let [c (net/send-resubscribe-message sub ann svc)]
        (async/go
          (let [msg (async/<! c)]
            (handle-subscribe-response msg (:dev-id sub) (:svc-id sub))))))))

(defn unsubscribe [sub-id]
  (let [sub (@*subscriptions* sub-id)]
    (net/send-unsubscribe-message (:sid sub)
                                  (discovery/find-announcement (:dev-id sub))
                                  (description/find-service (:dev-id sub) (:svc-id sub)))
    (remove-subscriptions *subscriptions* sub)))
