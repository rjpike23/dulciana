;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.client.events
  (:require [re-frame.core :as rf]
            [dulciana.client.db :as db]))

(rf/reg-event-fx
 :initialize-db
 (fn [{:keys [db]}]
   {:db (merge db/initial-state db)}))

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
 :devices-received
 (fn [db [_ devs]]
   (assoc-in db [:remote :devices] devs)))

(rf/reg-event-db
 :services-received
 (fn [db [_ svcs]]
   (assoc-in db [:remote :services] svcs)))

(rf/reg-event-db
 :announcements-received
 (fn [db [_ announcements]]
   (assoc-in db [:remote :announcements] announcements)))
