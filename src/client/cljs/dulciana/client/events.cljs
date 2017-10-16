(ns dulciana.client.events
  (:require [re-frame.core :as rf]
            [dulciana.client.db :as db]))

(rf/reg-event-fx
 :initialize-db
 (fn [{:keys [db]}]
   {:db db/initial-state}))

(rf/reg-event-db
 :devices-received
 (fn [db [_ devs]]
   (assoc db :devices devs)))

(rf/reg-event-db
 :services-received
 (fn [db [_ svcs]]
   (assoc db :services svcs)))
