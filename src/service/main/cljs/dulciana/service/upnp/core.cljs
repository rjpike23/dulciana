;  Copyright 2017-2018, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.core
  (:require [cljs.core.async :as async]
            [clojure.set :as set]
            [taoensso.timbre :as log :include-macros true]
            [url :as url]
            [dulciana.service.events :as events]
            [dulciana.service.net :as net]
            [dulciana.service.upnp.messages :as msg]
            [dulciana.service.upnp.discovery.core :as discovery]))

(defonce *announcements-sub* (atom nil))

(defonce *remote-devices* (atom {}))

(defonce *remote-devices-pub* (events/wrap-atom *remote-devices*))

(defonce *remote-devices-sub* (atom nil))

(defonce *remote-services* (atom {}))

(defonce *remote-services-pub* (events/wrap-atom *remote-services*))

(defonce *remote-services-sub* (atom nil))

(defonce *subscriptions* (atom {}))
 
(defn find-device [dev-id]
  (@*remote-devices* dev-id))

(defn find-service [dev-id svc-id]
  (let [dev (find-device dev-id)]
    (when dev
      (some #(when (= (:serviceId %) svc-id) %)
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

(defn send-control-request [url service-type action-name params]
  (let [msg (msg/emit-control-soap-msg service-type action-name params)
        hdrs {"USER-AGENT" "Unix/5.0 UPnP/2.0 dulciana/1.0"
              "SOAPACTION" (str "\"" service-type "#" action-name "\"")}]
    (net/send-http-request "POST" url {:body msg
                                       :headers hdrs} {})))

(defn submit-dev-desc-request [dev-id]
  (swap! *remote-devices*
         (fn [devs] (if (@discovery/*announcements* dev-id)
                      (assoc devs dev-id :pending)
                      (dissoc devs dev-id)))
         (fn [devs] (when-let [announcement (@discovery/*announcements* dev-id)]
                      (request-device-descriptor announcement)))))

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

(defn process-device-descriptor [dev-desc]
  (if (:error dev-desc)
    (do
      (log/error "Error retrieving dev desc:" dev-desc)
      (discovery/remove-announcements discovery/*announcements* (:announcement dev-desc)))
    (do
      (log/debug "Got dev desc:" (-> dev-desc :message :device :UDN))
      (swap! *remote-devices*
             (fn [devs]
               (assoc devs (-> dev-desc :message :device :UDN) (:message dev-desc)))
             (fn [devs]
               (doseq [svc-desc (-> dev-desc :message :device :serviceList)]
                 (request-service-descriptor (:announcement dev-desc) svc-desc)))))))

(defn process-service-descriptor [svc-desc]
  (if (:error svc-desc)
    (log/error "Error while accessing service descriptor:" (:message svc-desc))
    (do
      (log/debug "Got svc desc" (-> svc-desc :service-info :serviceId))
      (swap! *remote-services*
             (fn [svcs]
               (assoc svcs (discovery/create-usn
                            (discovery/get-dev-id (-> svc-desc :announcement :message :headers :usn))
                            (-> svc-desc :service-info :serviceId))
                      (svc-desc :message)))))))

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
   (let [svc (find-service device-id service-id)
         ann (discovery/find-announcement device-id)]
     (when (and svc ann)
       (let [c (net/send-subscribe-message ann svc)]
         (async/go
           (let [msg (async/<! c)]
             (handle-subscribe-response msg device-id service-id))))))))

(defn resubscribe [sub-id]
  (let [sub (@*subscriptions* sub-id)
        svc (find-service (:dev-id sub) (:svc-id sub))
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
                                  (find-service (:dev-id sub) (:svc-id sub)))
    (remove-subscriptions *subscriptions* sub)))

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
  (async/close! @*remote-services-sub*)
  (async/unsub *remote-devices-pub* :update @*remote-devices-sub*)
  (async/close! @*remote-devices-sub*)
  (async/unsub discovery/*announcements-pub* :update @*announcements-sub*)
  (async/close! @*announcements-sub*))
