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

(defn parse-edn [response]
  (into (sorted-map) (read-string response)))

(defn dispatch-response [event response]
  (log/info "RESPONSE RCVD" event)
  (rf/dispatch [event response]))

(defmulti dispatch-event-msg first)

(defmethod dispatch-event-msg :default
  [{:as msg :keys [id event]}]
  (log/info "No matching handler" msg))

(defmethod dispatch-event-msg :dulciana.service/update-devices
  [[id data]]
  (dispatch-response :devices-received (:data data)))

(defmethod dispatch-event-msg :dulciana.service/update-announcements
  [[id data]]
  (dispatch-response :announcements-received (:data data)))

(defmethod dispatch-event-msg :dulciana.service/update-services
  [[id data]]
  (dispatch-response :services-received (:data data)))

(defn event-msg-handler [msg]
  (dispatch-event-msg (:?data msg)))

(defn start-sente! []
  (let [{:keys [chsk ch-recv send-fn state] :as s}
        (sente/make-channel-socket-client! "/api/upnp/updates" {:type :auto :packer :edn})]
    (sente/start-client-chsk-router! ch-recv event-msg-handler)
    (reset! *event-channel* ch-recv)
    (reset! *event-sender* send-fn)))

(defn run []
  (start-sente!)
  (rf/dispatch-sync [:initialize-db])
  (ajax/GET "/api/upnp/devices" {:handler (comp (partial dispatch-response :devices-received) parse-edn)})
  (ajax/GET "/api/upnp/services" {:handler (comp (partial dispatch-response :services-received) parse-edn)})
  (ajax/GET "/api/upnp/announcements" {:handler (comp (partial dispatch-response :announcements-received) parse-edn)})
  (reagent/render (views/main-view)
                  (js/document.getElementById "app")))

(run)
