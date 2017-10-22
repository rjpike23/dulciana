(ns dulciana.client.views
  (:require [re-frame.core :as rf]
            [secretary.core :as secretary]))

(defn device-row-component [row]
  [:div.col-md
   [:div.card {:key (-> row :device :UDN)}
    [:button.card-title.btn.btn-primary
     {:on-click #(.setToken dulciana.client.core/history (str "upnp/device/" (-> row :device :UDN)))}
     (-> row :device :friendlyName)]
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
    [:h4.text-white "SSDP Services A"]]
   [device-table-component (rf/subscribe [:devices])]])

(defn ssdp-device-page [devid]
  [:div
   [:div.navbar.navbar-dark.bg-dark
    [:h4.text-white (str "Device: " @devid)]]])

(defn main-view []
  (let [active-view (rf/subscribe [:view])
        selected-device (rf/subscribe [:selected-device])]
    [(fn render-main []
        (case @active-view
          :all-devices [ssdp-devices-page]
          :device [ssdp-device-page selected-device]))]))
