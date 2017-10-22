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
 :devices-received
 (fn [db [_ devs]]
   (assoc-in db [:remote :devices] devs)))

(rf/reg-event-db
 :services-received
 (fn [db [_ svcs]]
   (assoc-in db [:remote :services] svcs)))
