(ns dulciana.client.events
  (:require [re-frame.core :as rf]
            [dulciana.client.db :as db]))

(rf/reg-event-fx
 :initialize-db
 (fn [{:keys [db]}]
   {:db db/initial-state}))

(rf/reg-event-db
 :change-view
 (fn [db [_ view]]
   (assoc-in db [:ui :active-view] view)))

(rf/reg-event-db
 :devices-received
 (fn [db [_ devs]]
   (assoc-in db [:remote :devices] devs)))

(rf/reg-event-db
 :services-received
 (fn [db [_ svcs]]
   (assoc-in db [:remote :services] svcs)))
