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
    (goog-events/listen EventType.NAVIGATE
                        (fn [event] (secretary/dispatch! (.-token event))))
    (.setEnabled true)))

(defroute "/upnp/devices" [] (rf/dispatch [:change-view :all-devices]))
(defroute "/upnp/device/:id" [id] (rf/dispatch [:change-view :device]))
(defroute "/" []
  #_(println "dispatched")
  (.setToken history "/upnp/devices"))

(defn run []
  (rf/dispatch-sync [:initialize-db])
  (ajax/GET "/devices" {:handler device-response-handler})
  (ajax/GET "/services" {:handler service-response-handler})
  (reagent/render (views/ssdp-devices-page)
                  (js/document.getElementById "app")))

(run)
