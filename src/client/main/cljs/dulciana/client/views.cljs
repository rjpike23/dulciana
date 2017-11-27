;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.client.views
  (:require [re-frame.core :as rf]
            [secretary.core :as secretary]
            [cemerick.url :as url]
            [taoensso.timbre :as log :include-macros true]))

(defn bootstrap-3by-table [components]
  (vec (concat [:div.col]
               (mapv (fn [partitions]
                       (vec (concat [:div.row] (mapv #(or % [:div.col]) partitions))))
                     (partition-all 3 components)))))

(defn device-card [device]
  [:div.col-md
   [:div.card {:key (-> device :device :UDN)}
    [:button.card-title.btn.btn-primary
     {:on-click #(.setToken dulciana.client.core/history (str "upnp/device/" (-> device :device :UDN)))}
     (-> device :device :friendlyName)]
    [:h6.card-subtitle (-> device :device :modelDescription)]
    [:dl.card-body
     [:dt "Manufacturer "] [:dd [:a {:href (-> device :device :manufacturerURL)} (-> device :device :manufacturer)]]
     [:dt "Model "] [:dd (-> device :device :modelName)]]]])

(defn device-table-component [devs]
  (bootstrap-3by-table (mapv (fn [[key val]] (device-card val)) @devs)))

(defn ssdp-devices-page []
  [:div
   [:div.navbar.navbar-dark.bg-dark
    [:h4.text-white "SSDP Devices"]]
   [device-table-component (rf/subscribe [:devices])]])

(defn service-card [location device svc]
  [:div.col-md
   [:div.card {:key (:serviceId svc)}
    [:button.card-title.btn.btn-primary
     {:on-click #(.setToken dulciana.client.core/history (str "upnp/device/" (:UDN device)
                                                              "/service/" (:serviceId svc)))}
     (:serviceId svc)]
    [:dl.card-body
     [:dt "ID"] [:dd (:serviceId svc)]
     [:dt "Type"] [:dd (:serviceType svc)]
     [:dt "SCPD URL"] [:dd [:a {:href (str (url/url location (:SCPDURL svc)))} (:SCPDURL svc)]]
     [:dt "Control URL"] [:dd (:controlURL svc)]
     [:dt "Event Sub URL"] [:dd (:eventSubURL svc)]]]])

(defn device-details-view []
  (let [dev (rf/subscribe [:selected-device])]
    [:div
     [:div.card
      [:dl.card-body
       [:dt "Manufacturer"] [:dd (-> @dev :dev :manufacturer)]
       [:dt "Model Name"] [:dd (-> @dev :dev :modelName)]
       [:dt "Friendly Name"] [:dd (-> @dev :dev :friendlyName)]
       [:dt "Model Number"] [:dd (-> @dev :dev :modelNumber)]
       [:dt "Device Type"] [:dd (-> @dev :dev :deviceType)]
       [:dt "UDN"] [:dd (-> @dev :dev :UDN)]
       [:dt "Serial Number"] [:dd (-> @dev :dev :serialNumber)]
       [:dt "Icon"]]
      [:div (bootstrap-3by-table (map (partial service-card ((-> @dev :announcement :message :headers) "location") (:dev @dev))
                                      (-> @dev :dev :serviceList)))]]]))

(defn ssdp-device-page []
  [:div
   [:div.navbar.navbar-dark.bg-dark
    [:h4.text-white (str "SSDP Device")]]
   [device-details-view]])

(defn service-state-variables [svc])

(defn service-details-view []
  (let [svc (rf/subscribe [:selected-service])]
    [:div
     [:div.card
      [:div.card-body "Service details here"]]]))

(defn ssdp-service-page []
  [:div
   [:div.navbar.navbar-dark.bg-dark
    [:h3.text-white (str "SSDP Service")]]
   [service-details-view]])

(defn main-view []
  (let [active-view (rf/subscribe [:view])]
    [(fn render-main []
        (case @active-view
          :all-devices [ssdp-devices-page]
          :device [ssdp-device-page]
          :service [ssdp-service-page]))]))
