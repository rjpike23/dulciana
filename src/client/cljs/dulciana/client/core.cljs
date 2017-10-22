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

(defn device-response-handler [response]
  (rf/dispatch [:devices-received (into (sorted-map) (read-string response))]))

(defn service-response-handler [response]
  (rf/dispatch [:services-received (into (sorted-map) (read-string response))]))

(defonce history
  (doto (Html5History.)
    (.setUseFragment false)
    (goog-events/listen EventType.NAVIGATE #(secretary/dispatch! (str (.-token %))))
    (.setEnabled true)))

(defroute "/upnp/devices" [] (rf/dispatch [:view-devices]))
(defroute "/upnp/device/:id" [id] (rf/dispatch [:view-device id]))

(defn run []
  (rf/dispatch-sync [:initialize-db])
  (ajax/GET "/api/upnp/devices" {:handler device-response-handler})
  (ajax/GET "/api/upnp/services" {:handler service-response-handler})
  (reagent/render (views/main-view)
                  (js/document.getElementById "app")))

(run)
