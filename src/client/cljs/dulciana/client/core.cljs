(ns dulciana.client.core
  (:require [cljs.reader :refer [read-string]]
            [reagent.core :as reagent]
            [ajax.core :as ajax]
            [taoensso.timbre :as timbre
             :refer-macros [log trace debug info warn error fatal report
                            logf tracef debugf infof warnf errorf fatalf reportf
                            spy get-env]]))

(enable-console-print!)

(defonce devices (reagent/atom {}))
(defonce services (reagent/atom {}))

(defn device-row-component [row]
  [:div.col-md [:div.card {:key (-> row :device :UDN)}
            [:button.card-title.btn.btn-primary {:on-click #(prn "I did something!")} (-> row :device :friendlyName)]
            [:h6.card-subtitle (-> row :device :modelDescription)]
            [:dl.card-body
             [:dt "Manufacturer "] [:dd [:a {:href (-> row :device :manufacturerURL)} (-> row :device :manufacturer)]]
             [:dt "Model "] [:dd (-> row :device :modelName)]]]])

(defn device-table-component [devs]
  (vec (concat [:div.col]
               (mapv (fn [x] 
                       [:div.row
                        (nth x 0 [:div.col])
                        (nth x 1 [:div.col])
                        (nth x 2 [:div.col])])
                     (partition-all 3 (mapv (fn [[key val]] (device-row-component val)) @devs))))))

(defn service-row-component [[id row]]
  [:tr {:key id}
   [:td id]])

(defn service-table-component [srvs]
  [:table.table.table-striped
   [:thead.thead-inverse
    [:tr [:th "Name"]]]
   [:tbody
    (map service-row-component @srvs)]])

(defn ssdp-devices-page [devices services]
  [:div
   [:div.navbar.navbar-dark.bg-dark
    [:h4.text-white "SSDP Services"]]
   [device-table-component devices]
   [service-table-component services]])

(defn device-response-handler [response]
  (reset! devices (into (sorted-map) (read-string response))))

(defn service-response-handler [response]
  (reset! services (into (sorted-map) (read-string response))))

(defn run []
  (reagent/render (ssdp-devices-page devices services)
                  (js/document.getElementById "app"))
  (ajax/GET "/devices" {:handler device-response-handler})
  (ajax/GET "/services" {:handler service-response-handler}))

(run)
