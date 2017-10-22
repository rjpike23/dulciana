(ns dulciana.client.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub
 :view
 (fn [db _]
   (-> db :ui :active-view)))

(rf/reg-sub
 :selected-device
 (fn [db _]
   (-> db :ui :device :selected-id)))

(rf/reg-sub
 :devices
 (fn [db _]
   (-> db :remote :devices)))

(rf/reg-sub
 :services
 (fn [db _]
   (-> db :remote :services)))
