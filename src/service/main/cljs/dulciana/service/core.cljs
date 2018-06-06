;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.core
  (:require-macros [hiccups.core :as hiccups :refer [html5]])
  (:require [cljs.core.async :as async]
            [cljs.nodejs :as nodejs]
            [hiccups.runtime :as hiccupsrt]
            [dulciana.service.net :as net]
            [dulciana.service.parser :as parser]
            [dulciana.service.state :as state]
            [dulciana.service.messages :as msg]
            [dulciana.service.ssdp.core :as ssdp]
            [taoensso.timbre :as log :include-macros true]
            [express :as express]
            [http :as http]
            [source-map-support :as sms]))

(nodejs/enable-util-print!)

(defonce *announcement-interval* 90000)

(defonce http-server (atom nil))
(defonce ssdp-announcement-timer (atom nil))

(defn template []
  (hiccups/html5 [:html
                  [:head
                   [:meta {:charset "utf-8"}]
                   [:meta {:name "viewport"
                           :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
                   [:link {:rel "stylesheet"
                           :href "https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0-beta/css/bootstrap.min.css"
                           :integrity "sha384-/Y6pD6FV/Vv2HJnA6t+vslU6fwYXjCFtcEpHbNJ0lyAFsXTsjBbfaDjzALeQsN6M"
                           :crossorigin "anonymous"}]]
                  [:body
                   [:div#app.container-fluid]
                   [:script {:src "https://code.jquery.com/jquery-3.2.1.slim.min.js"}]
                   [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/popper.js/1.11.0/umd/popper.min.js"}]
                   [:script {:src "https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0-beta/js/bootstrap.min.js"}]
                   [:script {:src "/resources/fig-client/dulciana_figwheel.js"
                             :type "text/javascript"}]]]))

(defn template-express-handler [req res]
  (. res (set "Access-Control-Allow-Origin" "*"))
  (. res (send (template))))

(def app (express))

(defn filter-pending [map]
  (into {} (filter (fn [[k v]] (not= :pending v)) map)))

(. app use "/resources" (. express (static "target")))
(. app (get "/api/upnp/announcements"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str @ssdp/*announcements*))))))
(. app (get "/api/upnp/devices"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str (filter-pending @state/remote-devices)))))))
(. app (get "/api/upnp/services"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str (filter-pending @state/remote-services)))))))
(. app (get "/api/upnp/services/:svcid"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str (@state/remote-services (.-svcid (.-params req)))))))))
(. app (get "/upnp/devices/"
            template-express-handler))
(. app (get "/upnp/device/:devid"
            template-express-handler))
(. app (get "/"
            (fn [req res]
              (. res (redirect "/upnp/devices")))))

(declare resubscribe)

#_(defn handle-subscribe-response [msg device-id service-id]
  (if (= (-> msg :message :status-code) 200)
    (let [sid (-> msg :message :headers :sid)
          delta-ts (js/parseInt (second (re-matches #"Second-(\d*)"
                                                    (-> msg :message :headers :timeout))))]
      (state/update-subscriptions state/subscriptions
                                  {:sid sid
                                   :expiration (js/Date. (+ (* 1000 delta-ts) (.getTime (js/Date.))))
                                   :dev-id device-id
                                   :svc-id service-id})
      (async/go
        (async/<! (async/timeout (* 1000 (- delta-ts 60)))) ; resub 60s before expiration.
        (when (@state/subscriptions sid) ; make sure still subbed.
          (resubscribe sid))))
    (log/warn "Error (" (-> msg :message :status-code) ":" (-> msg :message :status-msg)
              ") received from subscribe request, dev=" device-id "svc= " service-id)))

#_(defn subscribe
  ([device-id service-id]
   (subscribe device-id service-id []))
  ([device-id service-id state-var-list]
   (let [svc (state/find-service device-id service-id)
         ann (state/find-announcement device-id)]
     (when (and svc ann)
       (let [c (net/send-subscribe-message ann svc)]
         (async/go
           (let [msg (async/<! c)]
             (handle-subscribe-response msg device-id service-id))))))))

#_(defn resubscribe [sub-id]
  (let [sub (@state/subscriptions sub-id)
        svc (state/find-service (:dev-id sub) (:svc-id sub))
        ann (state/find-announcement (:dev-id sub))]
    (when (and svc ann)
      (let [c (net/send-resubscribe-message sub ann svc)]
        (async/go
          (let [msg (async/<! c)]
            (handle-subscribe-response msg (:dev-id sub) (:svc-id sub))))))))


#_(defn unsubscribe [sub-id]
  (let [sub (@state/subscriptions sub-id)]
    (net/send-unsubscribe-message (:sid sub)
                                  (state/find-announcement (:dev-id sub))
                                  (state/find-service (:dev-id sub) (:svc-id sub)))
    (state/remove-subscriptions state/subscriptions sub)))

(defn notify []
  (log/trace "Sending announcements"))

(defn start-notifications [interval]
  (reset! ssdp-announcement-timer (js/setInterval notify interval)))

(defn stop-notifications []
  (when @ssdp-announcement-timer
    (js/clearInterval @ssdp-announcement-timer))
  (reset! ssdp-announcement-timer nil))

;;; Initializes network connections / routes etc. Called from both
;;; -main and the figwheel reload hook.
(defn setup []
  (try
    ;(parser/start-ssdp-parser)
    ;(state/start-subscribers)
    ;(net/start-listeners)
    (start-notifications *announcement-interval*)
    (reset! http-server
            (doto (.createServer http #(app %1 %2))
              (.listen 3000)))
    (catch :default e
      (log/error "Error while starting Dulciana." e))))

(defn teardown []
  (stop-notifications)
  ;(ssdp/stop-listeners @net/sockets)
  ;(state/stop-subscribers)
  ;(parser/stop-ssdp-parser)
  (.close @http-server))

(defn fig-reload-hook []
  (teardown)
  (setup))

(defn -main [& args]
  (setup)
  (.on nodejs/process "beforeExit" teardown))

(set! *main-cli-fn* -main)
