;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.client.events
  (:require [ajax.edn]
            [re-frame.core :as rf]
            [day8.re-frame.http-fx]
            [taoensso.timbre :as log :include-macros true]
            [dulciana.client.db :as db]
            [dulciana.client.ws :as ws]))

(rf/reg-fx
 :send-ws-msg
 (fn [fx-arg]
   (if (:on-response fx-arg)
     (ws/send-event (:msg fx-arg)
                    (or (:timeout fx-arg) 3000)
                    (fn [response]
                      (rf/dispatch (:on-response fx-arg))))
     (ws/send-event (:msg fx-arg)))))

(rf/reg-event-fx
 :initialize-db
 (fn [{:keys [db]}]
   {:db (merge db/initial-state db)
    :dispatch-n (list [:request-devices] [:request-services] [:request-announcements])}))

(rf/reg-event-fx
 :log
 (fn [_ [_ resp]]
   (console.log "LOG-EVT" resp)))

(rf/reg-event-db
 :view-devices
 (fn [db _]
   (assoc-in db [:ui :active-view] :all-devices)))

(rf/reg-event-db
 :view-device
 (fn [db [_ devid]]
   (assoc-in (assoc-in db [:ui :active-view] :device)
             [:ui :device :selected-id] devid)))

(rf/reg-event-db
 :view-service
 (fn [db [_ devid svcid]]
   (assoc-in (assoc-in db [:ui :active-view] :service)
             [:ui :service :selected-id] (str devid "::" svcid))))

(rf/reg-event-db
 :select-action
 (fn [db [_ action]]
   (-> db
       (assoc-in [:ui :service :selected-action] action)
       (assoc-in [:ui :forms :invoke-action] {}))))

(rf/reg-event-fx
 :request-devices
 (fn [_ _]
   {:http-xhrio {:method :get
                 :uri "/api/upnp/devices"
                 :response-format (ajax.edn/edn-response-format)
                 :on-success [:devices-received]
                 :on-failure [:log]}}))

(rf/reg-event-fx
 :request-services
 (fn [_ _]
   {:http-xhrio {:method :get
                 :uri "/api/upnp/services"
                 :response-format (ajax.edn/edn-response-format)
                 :on-success [:services-received]
                 :on-failure [:log]}}))

(rf/reg-event-fx
 :request-announcements
 (fn [_ _]
   {:http-xhrio {:method :get
                 :uri "/api/upnp/announcements"
                 :response-format (ajax.edn/edn-response-format)
                 :on-success [:announcements-received]
                 :on-failure [:log]}}))

(rf/reg-event-db
 :devices-received
 (fn [db [_ devs]]
   (log/info ":devices-received")
   (assoc-in db [:remote :devices] devs)))

(rf/reg-event-db
 :services-received
 (fn [db [_ svcs]]
   (log/info ":services-received")
   (assoc-in db [:remote :services] svcs)))

(rf/reg-event-db
 :announcements-received
 (fn [db [_ announcements]]
   (log/info ":announcements-received")
   (assoc-in db [:remote :announcements] announcements)))

(rf/reg-event-db
 :update-form
 (fn [db [_ form-path values]]
   (update-in db form-path (fn [old] (merge old values)))))

(rf/reg-event-fx
 :invoke-action
 (fn [cofx _]
   (let [selected-device (-> (:db cofx) :ui :device :selected-id)
         selected-service (-> (:db cofx) :ui :service :selected-id) 
         selected-action (-> (:db cofx) :ui :service :selected-action)
         form-values (-> (:db cofx) :ui :forms :invoke-action)]
     {:send-ws-msg {:msg [:dulciana/invoke-action
                          {:data {:device selected-device
                                  :service selected-service
                                  :action (:name selected-action)
                                  :form form-values}}]}})))
