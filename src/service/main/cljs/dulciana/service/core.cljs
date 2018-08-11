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
            [dulciana.service.upnp.discovery.core :as discovery]
            [dulciana.service.upnp.description.core :as description]
            [dulciana.service.upnp.eventing.core :as eventing]
            [taoensso.timbre :as log :include-macros true]
            [express :as express]
            [http :as http]
            [source-map-support :as sms]))

(sms/install)
(nodejs/enable-util-print!)

(defonce *announcement-interval* 90000)

(defonce *http-server* (atom nil))
(defonce *ssdp-announcement-timer* (atom nil))

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
  (into {} (filter (fn [[k v]] (not= (type v) Atom)) map)))

(. app use "/resources" (. express (static "target")))
(. app (get "/api/upnp/announcements"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str @discovery/*announcements*))))))
(. app (get "/api/upnp/devices"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str (filter-pending @description/*remote-devices*)))))))
(. app (get "/api/upnp/services"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str (filter-pending @description/*remote-services*)))))))
(. app (get "/api/upnp/services/:svcid"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str (filter-pending (@description/*remote-services* (.-svcid (.-params req))))))))))
(. app (get "/upnp/devices/"
            template-express-handler))
(. app (get "/upnp/device/:devid"
            template-express-handler))
(. app (get "/upnp/device/:devid/service/:svcid"
            template-express-handler))
(. app (get "/"
            (fn [req res]
              (. res (redirect "/upnp/devices")))))

(defn notify []
  (log/trace "Sending announcements"))

(defn start-notifications [interval]
  (reset! *ssdp-announcement-timer* (js/setInterval notify interval)))

(defn stop-notifications []
  (when @*ssdp-announcement-timer*
    (js/clearInterval @*ssdp-announcement-timer*))
  (reset! *ssdp-announcement-timer* nil))

;;; Initializes network connections / routes etc. Called from both
;;; -main and the figwheel reload hook.
(defn setup []
  (try
    (start-notifications *announcement-interval*)
    (discovery/start-listeners)
    (description/start-listeners)
    (eventing/start-event-server)
    (reset! *http-server*
            (doto (.createServer http #(app %1 %2))
              (.listen 3000)))
    (catch :default e
      (log/error "Error while starting Dulciana." e))))

(defn teardown []
  (stop-notifications)
  (eventing/stop-event-server)
  (description/stop-listeners)
  (discovery/stop-listeners @discovery/*sockets*)
  (.close @*http-server*))

(defn fig-reload-hook []
  (teardown)
  (setup))

(defn -main [& args]
  (setup)
  (.on nodejs/process "beforeExit" teardown))

(set! *main-cli-fn* -main)
