;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.client.core
  (:require [cljs.reader :refer [read-string]]
            [ajax.core :as ajax]
            [devtools.core :as devtools]
            [goog.events :as goog-events]
            [reagent.core :as reagent]
            [re-frame.core :as rf]
            [secretary.core :as secretary :refer-macros [defroute]]
            [taoensso.timbre :as timbre
             :refer-macros [log trace debug info warn error fatal report
                            logf tracef debugf infof warnf errorf fatalf reportf
                            spy get-env]]
            [dulciana.client.events :as events]
            [dulciana.client.subs :as subs]
            [dulciana.client.views :as views])
  (:import [goog History]
           [goog.history Html5History EventType]))

(enable-console-print!)
(devtools/install!)

(defn parse-edn [response]
  (into (sorted-map) (read-string response)))

(defn dispatch-response [event response]
  (rf/dispatch [event (parse-edn response)]))

(defonce history
  (doto (Html5History.)
    (.setUseFragment false)
    (goog-events/listen EventType.NAVIGATE #(secretary/dispatch! (str (.-token %))))
    (.setEnabled true)))

(defroute "/upnp/devices" [] (rf/dispatch [:view-devices]))
(defroute "/upnp/device/:devid/service/:svcid" [devid svcid] (rf/dispatch [:view-service devid svcid]))
(defroute "/upnp/device/:id" [id] (rf/dispatch [:view-device id]))

(defn run []
  (rf/dispatch-sync [:initialize-db])
  (ajax/GET "/api/upnp/devices" {:handler (partial dispatch-response :devices-received)})
  (ajax/GET "/api/upnp/services" {:handler (partial dispatch-response :services-received)})
  (ajax/GET "/api/upnp/announcements" {:handler (partial dispatch-response :announcements-received)})
  (reagent/render (views/main-view)
                  (js/document.getElementById "app")))

(run)
