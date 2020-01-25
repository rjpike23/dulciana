;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.client.ws
  (:require [taoensso.sente :as sente]
            [re-frame.core :as rf]))

(defonce +event-channel+ (atom nil))
(defonce +event-sender+ (atom nil))

(defn send-event [msg & [timeout-ms callback]]
  (@+event-sender+ msg timeout-ms callback))

(defn dispatch-response [event response]
  (rf/dispatch [event response]))

(defmulti dispatch-event-msg first)

(defmethod dispatch-event-msg :default
  [{:as msg :keys [id event]}]) ;; Do nothing...

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
    (reset! +event-channel+ ch-recv)
    (reset! +event-sender+ send-fn)))
