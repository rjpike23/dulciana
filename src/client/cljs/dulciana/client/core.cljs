(ns dulciana.client.core
  (:require [devtools.core :as devtools]
            [cljs.reader :refer [read-string]]
            [reagent.core :as reagent]
            [re-frame.core :as rf]
            [secretary.core :as secretary :refer-macros [defroute]]
            [ajax.core :as ajax]
            [taoensso.timbre :as timbre
             :refer-macros [log trace debug info warn error fatal report
                            logf tracef debugf infof warnf errorf fatalf reportf
                            spy get-env]]
            [dulciana.client.events :as events]
            [dulciana.client.subs :as subs]
            [dulciana.client.views :as views]))

(enable-console-print!)
(devtools/install!)

(defn device-response-handler [response]
  (rf/dispatch [:devices-received (into (sorted-map) (read-string response))]))

(defn service-response-handler [response]
  (rf/dispatch [:services-received (into (sorted-map) (read-string response))]))

(defn run []
  (rf/dispatch-sync [:initialize-db])
  (ajax/GET "/devices" {:handler device-response-handler})
  (ajax/GET "/services" {:handler service-response-handler})
  (reagent/render (views/ssdp-devices-page)
                  (js/document.getElementById "app")))

(run)
