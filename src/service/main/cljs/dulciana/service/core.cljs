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
            [dulciana.service.upnp.core :as upnp]
            [dulciana.service.upnp.description.core :as description]
            [dulciana.service.upnp.discovery.core :as discovery]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.express :as sente-express]
            [taoensso.timbre :as log :include-macros true]
            [body-parser :as body-parser]
            [cookie-parser :as cookie-parser]
            [csurf :as csurf]
            [express :as express]
            [express-session :as express-session]
            [express-ws :as express-ws]
            [ws :as ws]
            [http :as http]
            [source-map-support :as sms]))

(nodejs/enable-util-print!)

(sms/install)

;; Websocket/Sente initialization:
(let [packer :edn
      {:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn connected-uids]}
      (sente-express/make-express-channel-socket-server! {:packer packer
                                                         :user-id-fn (fn [ring-req] (aget (:body ring-req) "session" "uid"))})]
  (def ajax-post ajax-post-fn)
  (def ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv)
  (def chsk-send! send-fn)
  (def connected-uids connected-uids))

(defonce *http-server* (atom nil))
(defonce *sente-router* (atom nil))

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

(defn filter-pending [map]
  (into {} (filter (fn [[k v]] (not= (type v) Atom)) map)))

(def app (express))
(def ws-app (express-ws app))

(doto app
  (.use (express-session #js {:secret "Dulciana-DLNA"
                      :resave true
                      :cookie {}
                      :store (.MemoryStore express-session)
                      :saveUninitialized true}))
  (.use (.urlencoded body-parser
                     #js {:extended false}))
  (.use (cookie-parser "Dulciana-DLNA"))
  (.use (csurf #js {:cookie false}))
  (.use "/resources" (. express (static "target")))
  (.get "/api/upnp/announcements"
     (fn [req res]
       (. res (set "Content-Type" "application/edn"))
       (. res (send (pr-str @discovery/*announcements*)))))
  (.get "/api/upnp/devices"
     (fn [req res]
       (. res (set "Content-Type" "application/edn"))
       (. res (send (pr-str (filter-pending @description/*remote-devices*))))))
  (.get "/api/upnp/services"
     (fn [req res]
       (. res (set "Content-Type" "application/edn"))
       (. res (send (pr-str (filter-pending @description/*remote-services*))))))
  (.get "/api/upnp/services/:svcid"
     (fn [req res]
       (. res (set "Content-Type" "application/edn"))
       (. res (send (pr-str (filter-pending (@description/*remote-services* (.-svcid (.-params req)))))))))
  (.ws "/api/upnp/updates"
     (fn [ws req]
       (ajax-get-or-ws-handshake req nil nil {:websocket? true
                                              :websocket ws})))
  (.get "/api/upnp/updates" ajax-get-or-ws-handshake)
  (.post "/api/upnp/updates" ajax-post)
  (.get "/upnp/devices/"
     template-express-handler)
  (.get "/upnp/device/:devid"
     template-express-handler)
  (.get "/upnp/device/:devid/service/:svcid"
     template-express-handler)
  (.get "/"
     (fn [req res]
       (. res (redirect "/upnp/devices")))))

(defn sente-event-handler [msg]
  (log/info "Received WS msg!" msg))

;;; Initializes network connections / routes etc. Called from both
;;; -main and the figwheel reload hook.
(defn setup []
  (try
    (upnp/start-upnp-services)
    (reset! *http-server*
            (doto (.createServer http #(app %1 %2))
              (.listen 3000)))
    (reset! *sente-router*
            (sente/start-chsk-router! ch-chsk sente-event-handler))
    (catch :default e
      (log/error "Error while starting Dulciana." e))))

(defn teardown []
  (upnp/stop-upnp-services)
  (when-let [stop-fn @*sente-router*] (stop-fn))
  (.close @*http-server*))

(defn fig-reload-hook []
  (teardown)
  (setup))

(defn -main [& args]
  (setup)
  (.on nodejs/process "beforeExit" teardown)
  (.on nodejs/process "uncaughtException" teardown))

(set! *main-cli-fn* -main)
