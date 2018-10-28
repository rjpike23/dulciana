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

(defn event-msg-handler [msg]
  (log/info "Event rcvd" (:event msg))
  (chsk-send! [:dulciana.client.core/msg {:data "hello dere"}]))

(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client! "/api/upnp/updates" {:type :auto :packer :edn})]
  (def chsk chsk)
  (def ch-chsk ch-recv)
  (def chsk-send! send-fn)
  (def chsk-state state)
  (sente/start-client-chsk-router! ch-chsk event-msg-handler))

(defn parse-edn [response]
  (into (sorted-map) (read-string response)))

(defn dispatch-response [event response]
  (log/info "RESPONSE RCVD" event response)
  (rf/dispatch [event (parse-edn response)]))

(defn run []
  (rf/dispatch-sync [:initialize-db])
  (ajax/GET "/api/upnp/devices" {:handler (partial dispatch-response :devices-received)})
  (ajax/GET "/api/upnp/services" {:handler (partial dispatch-response :services-received)})
  (ajax/GET "/api/upnp/announcements" {:handler (partial dispatch-response :announcements-received)})
  (reagent/render (views/main-view)
                  (js/document.getElementById "app"))
  (log/info "here we go 3"))

(run)
