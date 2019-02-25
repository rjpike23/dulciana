;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.client.views
  (:require [clojure.string :as str]
            [cljss.core :as css :include-macros true]
            [re-frame.core :as rf]
            [secretary.core :as secretary]
            [cemerick.url :as url]
            [taoensso.timbre :as log :include-macros true]
            [dulciana.client.routes :as routes]))

(css/defstyles dc-card []
  {:margin-top "8px"
   :margin-bottom "8px"})

(defn responsive-table [components]
  (vec (concat [:div.row] components)))

(defn device-card [device]
  (if (-> device :device :UDN)
    [:div.col-12.col-sm-6.col-md-4.col-lg-3 {:class (dc-card)}
     [:div.card {:key (-> device :device :UDN)}
      [:button.card-title.btn.btn-primary
       {:on-click #(.setToken routes/*history* (str "upnp/device/" (-> device :device :UDN)))}
       (-> device :device :friendlyName)]
      [:h6.card-subtitle (-> device :device :modelDescription)]
      [:dl.card-body
       [:dt "Manufacturer "] [:dd [:a {:href (-> device :device :manufacturerURL)} (-> device :device :manufacturer)]]
       [:dt "Model "] [:dd (-> device :device :modelName)]]]]
    (log/info "Dev with no UDN" device)))

(defn device-table-component [devs]
  (responsive-table (mapv (fn [[key val]] (device-card val)) @devs)))

(defn ssdp-devices-page []
  [:div
   [:nav.navbar.bg-primary
    [:div.navbar-brand.text-light "Dulciana / UPnP Browser"]
    [:ol.breadcrumb
     [:li.breadcrumb-item.active "All Devices"]]]
   [device-table-component (rf/subscribe [:devices])]])

(defn service-card [location device svc]
  [:div.col-12.col-sm-12.col-md-6.col-lg-4
   [:div.card {:key (:serviceId svc)}
    [:button.card-title.btn.btn-primary
     {:on-click #(.setToken routes/*history* (str "upnp/device/" (:UDN device)
                                                  "/service/" (:serviceId svc)))}
     (:serviceId svc)]
    [:dl.card-body
     [:dt "ID"] [:dd (:serviceId svc)]
     [:dt "Type"] [:dd (:serviceType svc)]
     [:dt "SCPD URL"] [:dd [:a {:href (str (url/url location (:SCPDURL svc)))} (:SCPDURL svc)]]
     [:dt "Control URL"] [:dd (:controlURL svc)]
     [:dt "Event Sub URL"] [:dd (:eventSubURL svc)]]]])

(defn device-details-view [dev]
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
    [:div (responsive-table (map (partial service-card (-> @dev :announcement :message :headers :location) (:dev @dev))
                                 (-> @dev :dev :serviceList)))]]])

(defn ssdp-device-page []
  (let [dev (rf/subscribe [:selected-device])]
    [:div
     [:nav.navbar.bg-primary
      [:div.navbar-brand.text-light "Dulciana / UPnP Browser"]
      [:ol.breadcrumb
       [:li.breadcrumb-item [:a {:href "#"
                                 :on-click (fn [evt]
                                             (.preventDefault evt)
                                             (.setToken routes/*history* "upnp/devices"))}
                             "All Devices"]]
       [:li.breadcrumb-item.active (-> @dev :dev :friendlyName)]]]
     [device-details-view dev]]))

(defn service-state-variable-card [state-var]
  [:div.col-12.col-sm-6.col-md-4.col-lg-3
   [:div.card {:class (dc-card)}
    [:h6.card-header.text-truncate (:name state-var)]
    [:div.card-body
     [:dl
      [:dt "Type"] [:dd.text-capitalize (:dataType state-var)]
      [:dt "Evented"] [:dd.text-capitalize (:sendEvents state-var)]
      (when-let [avs (:allowedValueList state-var)]
        [:div [:dt "Allowed values"] [:dd [:ul (for [av avs] [:li {:key av} av])]]])]]]])

(defn service-state-vars-view [svc]
  [:div
   [:div.card
    [:div.card-title [:h5 "State Variables"]]
    [:div.card-body (responsive-table (map service-state-variable-card (:serviceStateTable @svc)))]]])

(defn action-form [action form direction]
  [:form
   (for [in-arg (filter (fn [arg] (= direction (:direction arg)))
                        (:argumentList action))]
     [:div.form-group {:key (:name in-arg)}
      [:label {:for (:name in-arg)} (:name in-arg)]
      [:input.form-control {:id (:name in-arg)
                            :value (and form (form (keyword (:name in-arg))))
                            :onChange (fn [arg] (rf/dispatch [:update-form
                                                              [:ui :forms :invoke-action]
                                                              {(keyword (:name in-arg)) (.-value (.-target arg))}]))}]])])

(defn invoke-action-dialog []
  (let [action (rf/subscribe [:selected-action])
        form (rf/subscribe [:invoke-action-form])
        response (rf/subscribe [:action-response])]
     [:div.modal.fade {:id "launchDialog"}
     [:div.modal-dialog
      [:div.modal-content
       [:div.modal-header
        [:h5.modal-title "Invoke Action"]
        [:button.close {:type "button" :data-dismiss "modal"}
         [:span "\u00D7"]]] ;; x (close) icon
       [:div.modal-body
        [:h6.modal-title "Inputs"]
        [action-form @action @form "in"]]
       [:div.modal-body
        [:h6.modal-title "Outputs"]
        [action-form @action @response "out"]]
       [:div.modal-footer
        [:button.btn.btn-secondary {:data-dismiss "modal"} "Cancel"]
        [:button.btn.btn-primary {:onClick (fn [] (rf/dispatch [:invoke-action ]))} "Submit"]]]]]))

(defn service-actions-card [action]
  [:div.col-12.col-sm-6.col-md-4.col-lg-3
   [:div.card {:class (dc-card)}
    [:h6.card-header.text-truncate (:name action)]
    [:div.card-body
     [:button.btn.btn-primary {:data-toggle "modal"
                               :data-target "#launchDialog"
                               :on-click (fn [] (rf/dispatch [:select-action action]))}
      "Invoke"]
     [:dt "Arguments"]
     [:dd [:ul (for [arg (:argumentList action)]
                 [:li {:key (:name arg)} (:name arg)])]]]]])

(defn service-actions-view [svc]
  [:div.card
   [:div.card-title [:h6 "Actions"]]
   [:div.card-body (responsive-table (map service-actions-card (:actionList @svc)))]])

(defn ssdp-service-page []
  (let [dev (rf/subscribe [:selected-device])
        svc (rf/subscribe [:selected-service])
        svc-id (rf/subscribe [:selected-service-id])]
    [:div
     [:nav.navbar.bg-primary
      [:div.navbar-brand.text-light "Dulciana / UPnP Browser"]
      [:ol.breadcrumb
       [:li.breadcrumb-item [:a {:href "#"
                                 :on-click (fn [evt]
                                             (.preventDefault evt)
                                             (.setToken routes/*history* "upnp/devices"))}
                             "All Devices"]]
       [:li.breadcrumb-item [:a {:href "#"
                               :on-click (fn [evt]
                                           (.preventDefault evt)
                                           (.setToken routes/*history* (str "upnp/device/" (-> @dev :dev :UDN))))}
                             (-> @dev :dev :friendlyName)]]
       [:li.breadcrumb-item.active (last (str/split @svc-id ":"))]]]
     [service-actions-view svc]
     [service-state-vars-view svc]]))

(defn main-view []
  (let [active-view (rf/subscribe [:view])]
    [(fn render-main []
        (case @active-view
          :all-devices [ssdp-devices-page]
          :device [ssdp-device-page]
          :service [:div [ssdp-service-page] [invoke-action-dialog]]))]))
