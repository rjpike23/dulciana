;  Copyright 2017-2018, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.eventing.core
  (:require [cljs.core.async :as async]
            [taoensso.timbre :as log :include-macros true]
            [tubax.core :as xml]
            [tubax.helpers :as xml-util]
            [dulciana.service.events :as events]
            [dulciana.service.net :as net]
            [dulciana.service.parser :as parser]
            [dulciana.service.xml :as dulc-xml]
            [dulciana.service.upnp.discovery.core :as discovery]
            [dulciana.service.upnp.discovery.messages :as disc-msg]
            [dulciana.service.upnp.description.core :as description]
            [http :as http]))

(defonce *subscriptions* (atom {}))

;;; HTTP server socket for eventing:
(defonce *event-server* (atom {}))

(defonce *event-channel* (atom {}))

(defonce *event-pub* (atom {}))

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
    (log/warn "Error (" (-> msg :message :status-code) ":" (-> msg :message :status-message)
              ") received from subscribe request, dev" device-id "svc" service-id)))

(defn subscribe
  ([device-id service-id]
   (subscribe device-id service-id []))
  ([device-id service-id state-var-list]
   (let [svc (description/find-service device-id service-id)
         ann (discovery/find-announcement device-id)]
     (log/debug "Subscribe called" svc ann)
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

;;; Handler for UPNP pub-sub events
(defn respond [response code message]
  (set! (.-statusCode response) code)
  (set! (.-statusMessage response) message)
  (.end response))

(defn event-parse [msg]
  (try
    (assoc msg :message {:body (xml/xml->clj (-> msg :message :body))
                         :type :NOTIFY
                         :headers (-> msg :message :headers)})
    (catch :default e
      ((:error msg) 400 "Malformed message")
      (throw e))))

(defn event-analyze [msg]
  (let [m (dulc-xml/munge-namespaces (-> msg :message :body) {})]
    (assoc msg :message {:body (apply merge ((dulc-xml/xml-list
                                              {["property" "urn:schemas-upnp-org:event-1-0"] (dulc-xml/xml-map {} :include-unspec-elt true)})
                                             m))
                         :type :NOTIFY
                         :headers (-> msg :message :headers)})))

(defn handle-pub-server-request
  "Callback for when a pub-sub data event is received from an active socket."
  [src request response]
  (log/debug "Event server received" (.-method request) "request")
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
  (reset! *event-channel* (async/chan 1 (comp (map event-parse)
                                              (map event-analyze))))
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
  (.close @*event-server*)
  (async/close! @*event-channel*))
