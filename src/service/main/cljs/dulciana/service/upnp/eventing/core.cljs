;  Copyright 2017-2018, Radcliffe J. Pike. All rights reserved.
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

;;; HTTP server socket for eventing:
(defonce *event-server* (atom {}))

(defonce *event-channel* (atom {}))

(defonce *event-pub* (atom {}))

(defn update-subscription-state [event]
  (let [sid (-> event :message :headers :sid)]
    (if (@store/*external-subscriptions* sid)
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
      (update-subscriptions store/*external-subscriptions*
                                  {:sid sid
                                   :expiration (js/Date. (+ (* 1000 delta-ts) (.getTime (js/Date.))))
                                   :dev-id device-id
                                   :svc-id service-id})
      (async/go
        (async/<! (async/timeout (* 1000 (- delta-ts 60)))) ; resub 60s before expiration.
        (when (@store/*external-subscriptions* sid) ; make sure still subbed.
          (resubscribe sid))))
    (log/warn "Error (" (-> msg :message :status-code) ":" (-> msg :message :status-message)
              ") received from subscribe request, dev" device-id "svc" service-id)))

(defn subscribe
  ([device-id service-id]
   (subscribe device-id service-id []))
  ([device-id service-id state-var-list]
   (let [svc (store/find-service device-id service-id)
         ann (store/find-announcement device-id)]
     (log/debug "Subscribe called" svc ann)
     (when (and svc ann)
       (let [c (net/send-subscribe-message ann svc)]
         (async/go
           (let [msg (async/<! c)]
             (handle-subscribe-response msg device-id service-id))))))))

(defn resubscribe [sub-id]
  (let [sub (@store/*external-subscriptions* sub-id)
        svc (store/find-service (:dev-id sub) (:svc-id sub))
        ann (store/find-announcement (:dev-id sub))]
    (when (and svc ann)
      (let [c (net/send-resubscribe-message sub ann svc)]
        (async/go
          (let [msg (async/<! c)]
            (handle-subscribe-response msg (:dev-id sub) (:svc-id sub))))))))

(defn unsubscribe [sub-id]
  (let [sub (@store/*external-subscriptions* sub-id)]
    (net/send-unsubscribe-message (:sid sub)
                                  (store/find-announcement (:dev-id sub))
                                  (store/find-service (:dev-id sub) (:svc-id sub)))
    (remove-subscriptions store/*external-subscriptions* sub)))

;;; Handler for UPNP pub-sub events
(defn respond [response code message]
  (set! (.-statusCode response) code)
  (set! (.-statusMessage response) message)
  (.end response))

(defn validate-subscribe-request [usn statevar timeout callback]
  (let [svc (store/find-local-service usn)]
    (when (and svc callback)
      true)))

(defn handle-subscribe-request [req res]
  (let [usn (.-usn (.-params req))
        sid (.get req "SID")]
    (if sid
      () ; TODO resubscribe
      (let [statevar (.get req "STATEVAR") ; TODO subscribe
            timeout (.get req "TIMEOUT")
            callback (.get req "CALLBACK")]
        (if (validate-subscribe-request usn statevar timeout callback)
          (let [sub (store/create-subscription usn callback statevar (js/Date.) timeout)]
            (assoc @store/*internal-subscriptions* (:sid sub) sub)
            (doto res
              (.set "DATE" (:timestamp sub))
              (.set "SERVER" "FreeBSD/11.0 UPnP/2.0 Dulciana/1.0")
              (.set "SID" (:sid sub))
              (.set "TIMEOUT" (str "Seconds-" (:timeout sub)))
              (.set "ACCEPTED-STATEVAR" (str/join "," statevar))))
          (.sendError res 400))))))

(defn handle-unsubscribe-request [req res]
  (let [usn (.-usn (.-params req))]
    (if (store/find-local-service usn)
      (do
        (dissoc @store/*internal-subscriptions* usn)
        (.sendStatus 200))
      (.sendStatus 412))))

(defn handle-event-notification [req res]
  (let [parsed-req (event-msg/event-parse (.-body req))]))

(defn handle-pub-server-request
  "Callback for when a pub-sub data event is received from an active socket."
  [src request response]
  (log/debug "Event server received a" (.-method request) "request")
  (case (.-method request)
    "NOTIFY" (let [c (async/chan 1 (map (fn [msg]
                                          {:message {:type :NOTIFY
                                                     :body msg
                                                     :headers (js->clj (.-headers request)
                                                                       :keywordize-keys true)}
                                           :ok #(respond response 200 "OK")
                                           :error (partial respond response)})))]
               (events/slurp c request)
               (async/pipe c @*event-channel* false))
    (do
      (log/warn "Event server ignoring" (.-method request) "request")
      (.writeHead response 405 "Method Not Allowed" (clj->js {"Allow" "NOTIFY"}))
      (.end response "Method not allowed."))))

;;; Fns to manage the HTTP server which supports eventing.
(defn start-event-server
  ""
  []
  (reset! *event-channel* (async/chan 1 (comp (map event-msg/event-parse)
                                              (map event-msg/event-analyze))))
  (reset! *event-pub* (async/pub @*event-channel* :type))
  (let [server (.createServer http)
        evt-channels (events/listen* server ["request" "close"])]
    (log/info "Starting event server.")
    (async/go-loop []
      (let [req (async/<! (:request evt-channels))]
           (when req
             (apply handle-pub-server-request req)
             (recur))))
    (async/go (async/<! (:close evt-channels))
        (map async/close! (vals evt-channels))
        (log/info "Event server closed."))
    (.listen server net/*event-server-port*)
    (reset! *event-server* server)))

(defn stop-event-server
  ""
  []
  (when @*event-server*
    (.close @*event-server*))
  (async/close! @*event-channel*))
