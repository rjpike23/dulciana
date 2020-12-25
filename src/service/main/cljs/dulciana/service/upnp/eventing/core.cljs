;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.eventing.core
  (:require [cljs.core.async :as async]
            [clojure.string :as str]
            [taoensso.timbre :as log :include-macros true]
            [tubax.core :as xml]
            [tubax.helpers :as xml-util]
            [dulciana.service.events :as events]
            [dulciana.service.net :as net]
            [dulciana.service.parser :as parser]
            [dulciana.service.store :as store]
            [dulciana.service.xml :as dulc-xml]
            [dulciana.service.upnp.discovery.core :as discovery]
            [dulciana.service.upnp.discovery.messages :as disc-msg]
            [dulciana.service.upnp.description.core :as description]
            [dulciana.service.upnp.eventing.messages :as event-msg]
            [http :as http]))

(defonce +event-queue-flag+ (atom false))

(defonce +sub-event-channel+ (atom {}))

(defonce +sub-event-pub+ (atom {}))

(defonce +pub-event-channel+ (atom {}))

(defonce +pub-event-mix+ (atom nil))

;;; The following functions handle the case where dulciana is subscribing to
;;; an external service/device. 
(defn update-subscription-state [event]
  (let [sid (-> event :message :headers :sid)]
    (if (@store/+subscriptions+ sid)
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
      (update-subscriptions store/+subscriptions+
                                  {:sid sid
                                   :expiration (js/Date. (+ (* 1000 (if (pos? delta-ts) delta-ts 600))
                                                            (.getTime (js/Date.))))
                                   :dev-id device-id
                                   :svc-id service-id})
      (async/go
        (async/<! (async/timeout (* 1000 (- (if (pos? delta-ts) delta-ts 600) 60)))) ; resub 60s before expiration.
        (when (@store/+subscriptions+ sid) ; make sure still subbed.
          (resubscribe sid))))
    (log/warn "Error (" (-> msg :message :status-code) ":" (-> msg :message :status-message)
              ") received from subscribe request, dev" device-id "svc" service-id)))

(defn subscribe
  ([device-id service-id]
   (subscribe device-id service-id []))
  ([device-id service-id state-var-list]
   (let [svc (store/find-service device-id service-id)
         ann (store/find-announcement device-id)]
     (when (and svc ann)
       (let [c (net/send-subscribe-message ann svc)]
         (async/go
           (let [msg (async/<! c)]
             (handle-subscribe-response msg device-id service-id))))))))

(defn resubscribe [sub-id]
  (let [sub (@store/+subscriptions+ sub-id)
        svc (store/find-service (:dev-id sub) (:svc-id sub))
        ann (store/find-announcement (:dev-id sub))]
    (when (and svc ann)
      (let [c (net/send-resubscribe-message sub ann svc)]
        (async/go
          (let [msg (async/<! c)]
            (handle-subscribe-response msg (:dev-id sub) (:svc-id sub))))))))

(defn unsubscribe [sub-id]
  (let [sub (@store/+subscriptions+ sub-id)]
    (when sub
      (net/send-unsubscribe-message (:sid sub)
                                    (store/find-announcement (:dev-id sub))
                                    (store/find-service (:dev-id sub) (:svc-id sub)))
      (remove-subscriptions store/+subscriptions+ sub))))

;;; Handler for UPNP events
(defn respond [response code message]
  (set! (.-statusCode response) code)
  (set! (.-statusMessage response) message)
  (.end response))

(defn handle-pub-server-request
  "Callback for when a pub-sub data event is received from an active socket."
  [request response]
  (case (.-method request)
    "NOTIFY" (async/go
               (respond response 200 "OK")
               (async/>! @+sub-event-channel+
                         {:type :NOTIFY
                          :message {:body (.toString (.-body request))
                                    :headers (.-headers request)}
                          :error #(respond response %1 %2)
                          :ok #(respond response %1 %2)}))
    (do
      (log/warn "Event server ignoring" (.-method request) "request")
      (.writeHead response 405 "Method Not Allowed" (clj->js {"Allow" "NOTIFY"}))
      (.end response "Method not allowed."))))

;;; The remaining functions handle subscription requests to
;;; dulciana services from external services.
(defn validate-subscribe-request [usn statevar timeout callback]
  (let [svc (store/find-local-service usn)]
    (when (and svc callback)
      true)))

(defn send-subscribe-response [res pub]
  (doto res
    (.set "DATE" (:timestamp pub))
    (.set "SERVER" "FreeBSD/11.0 UPnP/2.0 Dulciana/1.0")
    (.set "SID" (:sid pub))
    (.set "TIMEOUT" (str "Seconds-" (:timeout pub)))
    (.set "ACCEPTED-STATEVAR" (str/join "," (:statevar pub)))
    (.sendStatus 200)))

(defn handle-subscribe-request
  "Cretes a new publication to deliver state change events to."
  [req res]
  (let [usn (.-usn (.-params req))
        timeout (.get req "TIMEOUT")
        sid (.get req "SID")]
    (if sid ; resubscribe
      (let [pub (@store/+publications+ sid)]
        (when pub
          (set! (.-timestamp pub) (js/Date.))
          (set! (.-timeout pub) timeout)
          (send-subscribe-response res pub)))
      (let [statevar (.get req "STATEVAR") ; subscribe
            callback (.get req "CALLBACK")]
        (if (validate-subscribe-request usn statevar timeout callback)
          (let [pub (store/create-publication usn callback statevar (js/Date.) timeout)]
            (swap! store/+publications+ assoc (:sid pub) pub)
            (send-subscribe-response res pub))
          (.sendError res 400))))))

(defn handle-unsubscribe-request [req res]
  (let [sid (.-sid (.-headers req))]
    (if sid
      (do
        (swap! store/+publications+ dissoc sid)
        (.sendStatus res 200))
      (.sendStatus res 412))))

(defn strip-callback-url [callback-spec]
  (subs callback-spec 1 (- (count callback-spec) 1)))

(defn send-event [publication properties]
  (let [url (strip-callback-url (:callback publication))]
    (net/send-http-request "NOTIFY" url
                           {"HOST" "abc"
                            "CONTENT-TYPE" "text/xml"
                            "NT" "upnp:event"
                            "NTS" "upnp:propchange"
                            "SID" (:sid publication)
                            "SEQ" (:seq-number publication)}
                           (event-msg/emit-event properties)
                           {})))

(defn start-event-queue-processor []
  (reset! +event-queue-flag+ true)
  (async/go-loop []
    (let [evt (async/<! @+pub-event-channel+)]
      (when evt
        (doseq [pub (store/find-publications (:usn evt))]
          (send-event
           pub
           (select-keys (:statevar pub) (:update evt)))))
      (when @+event-queue-flag+
        (recur)))))

;;; Fns to manage the channels for eventing.
(defn start-event-server
  ""
  []
  (reset! +sub-event-channel+ (async/chan 1 (comp (map event-msg/event-parse)
                                                  (map event-msg/event-analyze))))
  (events/channel-driver @+sub-event-channel+ (fn [msg]
                                                ((:ok msg) 200 "OK")))
  ;(reset! +sub-event-pub+ (async/pub @+sub-event-channel+ :type))
  (reset! +pub-event-channel+ (async/chan))
  (reset! +pub-event-mix+ (async/mix @+pub-event-channel+))
  (start-event-queue-processor))

(defn stop-event-server
  ""
  []
  (async/close! @+pub-event-channel+)
  (async/close! @+sub-event-channel+)
  (reset! +event-queue-flag+ false))
