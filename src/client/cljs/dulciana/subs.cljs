(ns dulciana.client.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub
 :devices
 (fn [db _]
   (:devices db)))

(rf/reg-sub
 :services
 (fn [db _]
   (:services db)))
