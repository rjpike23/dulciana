;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.core
  (:require-macros [hiccups.core :as hiccups :refer [html5]])
  (:require [cljs.core.async :as async]
            [cljs.nodejs :as nodejs]
            [hiccups.runtime :as hiccupsrt]
            [dulciana.service.config :as config]
            [dulciana.service.events :as events]
            [dulciana.service.net :as net]
            [dulciana.service.store :as store]
            [dulciana.service.upnp.core :as upnp]
            [dulciana.service.upnp.control.core :as control]
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

(defonce +event-channel+ (atom nil))
(defonce +event-sender+ (atom nil))
(defonce +event-connections+ (atom nil))
(defonce +http-server+ (atom nil))
(defonce +ws-server+ (atom nil))

(defn template []
  (hiccups/html5 [:html
                  [:head
                   [:meta {:charset "utf-8"}]
                   [:meta {:name "viewport"
                           :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
                   [:link {:rel "stylesheet"
                           :href "https://stackpath.bootstrapcdn.com/bootstrap/4.1.3/css/bootstrap.min.css"
                           :integrity "sha384-MCw98/SFnGE8fJT3GXwEOngsV7Zt27NXFoaoApmYm81iuXoPkFOJwJ8ERdknLPMO"
                           :crossorigin "anonymous"}]]
                  [:body
                   [:div#app.container-fluid]
                   [:script {:src "https://code.jquery.com/jquery-3.3.1.slim.min.js"
                             :integrity "sha384-q8i/X+965DzO0rT7abK41JStQIAqVgRVzpbzo5smXKp4YfRvH+8abtTE1Pi6jizo"
                             :crossorigin "anonymous"}]
                   [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/popper.js/1.14.3/umd/popper.min.js"
                             :integrity "sha384-ZMP7rVo3mIykV+2+9J3UJ46jBk0WLaUAdn689aCwoqbBJiSnjAK/l8WvCWPIPm49"
                             :crossorigin "anonymous"}]
                   [:script {:src "https://stackpath.bootstrapcdn.com/bootstrap/4.1.3/js/bootstrap.min.js"
                             :integrity "sha384-ChfqqxuZUCnJSK3+MXmPNIyE6ZbWh2IMqE241rYiqJxyMiZ6OW/JmZQ5stwEULTy"
                             :crossorigin "anonymous"}]
                   [:script {:src "/resources/fig-client/dulciana_figwheel.js"
                             :type "text/javascript"}]]]))

(defn template-express-handler [req res]
  (. res (set "Access-Control-Allow-Origin" "*"))
  (. res (send (template))))

(defn filter-pending [map]
  (into {} (filter (fn [[k v]] (not= (type v) Atom)) map)))

(defn start-sente! []
  (let [{:keys [ch-recv send-fn connected-uids
                ajax-get-or-ws-handshake-fn ajax-post-fn]
         :as s}
        (sente-express/make-express-channel-socket-server!
         {:packer :edn
          :user-id-fn (constantly "DLNA-DB-SERVICE")
          :csrf-token-fn nil})]
    (reset! +event-channel+ ch-recv)
    (reset! +event-sender+ send-fn)
    (reset! +event-connections+ connected-uids)
    s))

(defn start-express-server! [event-mgr]
  (let [express-app (express)
        express-ws-app (express-ws express-app)]
    (doto express-app
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
              (. res (send (pr-str @store/+announcements+)))))
      (.get "/api/upnp/devices"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str (filter-pending @store/+remote-devices+))))))
      (.get "/api/upnp/services"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str (filter-pending @store/+remote-services+))))))
      (.get "/api/upnp/services/:svcid"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str (filter-pending (@store/+remote-services+ (.-svcid (.-params req)))))))))
      (.ws "/api/upnp/updates"
           (fn [ws req next]
             ((:ajax-get-or-ws-handshake-fn event-mgr) req nil nil {:websocket? true
                                                                    :websocket ws})))
      (.get "/api/upnp/updates"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              ((:ajax-get-or-ws-handshake-fn event-mgr) req res)))
      (.post "/api/upnp/updates" (:ajax-post-fn event-mgr))
      (.get "/upnp/devices/"
            template-express-handler)
      (.get "/upnp/device/:devid"
            template-express-handler)
      (.get "/upnp/device/:devid/service/:svcid"
            template-express-handler)
      (.get "/"
            (fn [req res]
              (. res (redirect "/upnp/devices")))))
    (reset! +http-server+ (.listen express-app (config/get-value :dulciana-port)))
    (reset! +ws-server+ express-ws-app)))

(defn sente-router [ch hndlr]
  (events/channel-driver ch hndlr))

(defn sente-event-handler [msg]
  (let [[evt-type args] (:event msg)]
    (if (= evt-type :dulciana/invoke-action)
      (let [{:keys [device service action form]} (:data args)]
        (async/take! (control/send-control-request (description/get-control-url service)
                                                   (store/get-svc-type service)
                                                   action
                                                   form)
                     (fn [response]
                       (when (:?reply-fn msg)
                         ((:?reply-fn msg) {:device device
                                            :service service
                                            :action action
                                            :response response}))))))))

(defn send-event [msg]
  (@+event-sender+ "DLNA-DB-SERVICE" msg))

(defn send-db-update [tag data]
  (send-event [tag {:data data}]))

;;; Initializes network connections / routes etc. Called from both
;;; -main and the figwheel reload hook.
(defn setup []
  (try
    (upnp/start-upnp-services)
    (start-express-server! (start-sente!))
    (sente-router @+event-channel+ sente-event-handler)
    (add-watch store/+announcements+ :update
               (fn [key atom old new]
                 (when (not= old new)
                   (send-db-update :dulciana.service/update-announcements new))))
    (add-watch store/+remote-devices+ :update
               (fn [key atom old new]
                 (when (not= old new)
                   (send-db-update :dulciana.service/update-devices (filter-pending new)))))
    (add-watch store/+remote-services+ :update
               (fn [key atom old new]
                 (when (not= old new)
                   (send-db-update :dulciana.service/update-services (filter-pending new)))))
    (catch :default e
      (log/error "Error while starting Dulciana XXX." e))))

(defn teardown
  ([arg1 arg2]
   (println "Uncaught Exception XXX!" arg1 arg2)
   (teardown))
  ([]
   (try
     (remove-watch store/+announcements+ :update)
     (remove-watch store/+remote-devices+ :update)
     (remove-watch store/+remote-services+ :update)
     (upnp/stop-upnp-services)
     (when @+http-server+ (.close @+http-server+))
     (when @+event-channel+) (async/close! @+event-channel+)
     (catch :default e
       (log/error "Error shutting down Dulciana XXX." e)))))

(defn fig-reload-hook []
  (teardown)
  (setup))

(defn -main [& args]
  (setup)
  (.on nodejs/process "beforeExit" teardown)
  (.on nodejs/process "uncaughtException" teardown))

(set! *main-cli-fn* -main)

