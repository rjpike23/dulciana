;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.


(ns dulciana.client.core
  (:require [cljs.reader :refer [read-string]]
            [ajax.core :as ajax]
            [devtools.core :as devtools]
            [reagent.core :as reagent]
            [re-frame.core :as rf]
            [taoensso.sente :as sente]
            [taoensso.timbre :as log :include-macros true]
            [dulciana.client.events :as events]
            [dulciana.client.subs :as subs]
            [dulciana.client.views :as views]
            [dulciana.client.routes :as routes]))

(enable-console-print!)
(devtools/install!)

(defonce *event-channel* (atom nil))
(defonce *event-sender* (atom nil))

(defn send-event [msg]
  (@*event-sender* msg))

(defn event-msg-handler [msg]
  (log/info "Event rcvd" (:event msg))
  (send-event [:dulciana.client.core/msg {:data "hello dere"}]))

(defn start-sente! []
  (let [{:keys [chsk ch-recv send-fn state] :as s}
        (sente/make-channel-socket-client! "/api/upnp/updates" {:type :auto :packer :edn})]
    (sente/start-client-chsk-router! ch-recv event-msg-handler)
    (reset! *event-channel* ch-recv)
    (reset! *event-sender* send-fn)))

(defn parse-edn [response]
  (into (sorted-map) (read-string response)))

(defn dispatch-response [event response]
  (log/info "RESPONSE RCVD" event response)
  (rf/dispatch [event (parse-edn response)]))

(defn run []
  (start-sente!)
  (rf/dispatch-sync [:initialize-db])
  (ajax/GET "/api/upnp/devices" {:handler (partial dispatch-response :devices-received)})
  (ajax/GET "/api/upnp/services" {:handler (partial dispatch-response :services-received)})
  (ajax/GET "/api/upnp/announcements" {:handler (partial dispatch-response :announcements-received)})
  (reagent/render (views/main-view)
                  (js/document.getElementById "app")))

(run)
