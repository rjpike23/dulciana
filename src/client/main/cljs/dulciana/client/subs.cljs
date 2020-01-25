;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.client.subs
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [taoensso.timbre :as log :include-macros true]))

(rf/reg-sub
 :view
 (fn [db _]
   (-> db :ui :active-view)))

(rf/reg-sub
 :devices
 (fn [db _]
   (-> db :remote :devices)))

(rf/reg-sub
 :services
 (fn [db _]
   (-> db :remote :services)))

(rf/reg-sub
 :announcements
 (fn [db _]
   (-> db :remote :announcements)))

(rf/reg-sub
 :selected-device-id
 (fn [db _]
   (-> db :ui :device :selected-id)))

(rf/reg-sub
 :selected-service-id
 (fn [db _]
   (-> db :ui :service :selected-id)))

(defn services-by-device [device-id services]
  (into {} (filter (fn [[key svc]] (str/starts-with? key device-id))
                   services)))

(rf/reg-sub
 :merged-devices
 (fn [_ _]
   [(rf/subscribe [:devices])
    (rf/subscribe [:announcements])
    (rf/subscribe [:services])])
 (fn [[devices announcements services] _]
   (let [dev-anns (select-keys announcements (set (keys devices)))
         svc-anns (select-keys announcements (set (keys services)))
         merged-svcs (merge-with (fn [s a] {:svc s :announcement a})
                                 services svc-anns)
         merged-devs (merge-with (fn [d a] {:dev (:device d) :announcement a})
                                 devices dev-anns)]
     (into {} (map (fn [[id dev]]
                     [id (assoc dev :svcs (services-by-device id merged-svcs))])
                   merged-devs)))))

(rf/reg-sub
 :selected-device
 (fn [_ _]
   [(rf/subscribe [:selected-device-id])
    (rf/subscribe [:merged-devices])])
 (fn [[selected-id devices] _]
   (devices selected-id)))

(rf/reg-sub
 :selected-service
 (fn [_ _]
   [(rf/subscribe [:selected-service-id])
    (rf/subscribe [:services])])
 (fn [[svc-id services]]
   (services svc-id)))

(rf/reg-sub
 :selected-action
 (fn [db _]
   (-> db :ui :service :selected-action)))

(rf/reg-sub
 :action-form-values
 (fn [db _]
   (-> db :ui :forms :invoke-action)))

(rf/reg-sub
 :default-action-form
 (fn [_ _]
   [(rf/subscribe [:selected-action])])
 (fn [[action]]
   (into {} (map (fn [v] [(keyword (:name v)) ""])
                 (filter (fn [arg] (= "in" (:direction arg)))
                         (:argumentList action))))))

(rf/reg-sub
 :invoke-action-form
 (fn [db _]
   [(rf/subscribe [:default-action-form])
    (rf/subscribe [:action-form-values]) {}])
 (fn [[default current-vals]]
   (merge default current-vals)))

(rf/reg-sub
 :action-responses
 (fn [db _]
   (-> db :remote :actions)))

(rf/reg-sub
 :action-response
 (fn [_ _]
   [(rf/subscribe [:action-responses])
    (rf/subscribe [:selected-device-id])
    (rf/subscribe [:selected-service-id])
    (rf/subscribe [:selected-action])])
 (fn [[responses dev svc action]]
    (if (and responses dev svc action)
     (get-in responses [dev svc (:name action)])
     {})))
