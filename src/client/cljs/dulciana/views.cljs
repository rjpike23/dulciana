(ns dulciana.client.views
  (:require [re-frame.core :as rf]))

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

(defn ssdp-devices-page []
  [:div
   [:div.navbar.navbar-dark.bg-dark
    [:h4.text-white "SSDP Services"]]
   [device-table-component (rf/subscribe [:devices])]
   [service-table-component (rf/subscribe [:services])]])
