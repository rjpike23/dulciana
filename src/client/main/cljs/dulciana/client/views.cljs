(ns dulciana.client.views
  (:require [re-frame.core :as rf]
            [secretary.core :as secretary]
            [cemerick.url :as url]))

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
  (vec (concat [:div.col]
               (mapv (fn [x] 
                       [:div.row
                        (nth x 0 [:div.col])
                        (nth x 1 [:div.col])
                        (nth x 2 [:div.col])])
                     (partition-all 3 (mapv (fn [[key val]] (device-card val)) @devs))))))

(defn ssdp-devices-page []
  [:div
   [:div.navbar.navbar-dark.bg-dark
    [:h4.text-white "SSDP Devices"]]
   [device-table-component (rf/subscribe [:devices])]])

(defn service-card [location svc]
  (println location)
  [:div.card {:key (:serviceId svc)}
   [:dl.card-body
    [:dt "ID"] [:dd (:serviceId svc)]
    [:dt "Type"] [:dd (:serviceType svc)]
    [:dt "SCPD URL"] [:dd [:a {:href (str (url/url location (:SCPDURL svc)))} (:SCPDURL svc)]]
    [:dt "Control URL"] [:dd (:controlURL svc)]
    [:dt "Event Sub URL"] [:dd (:eventSubURL svc)]]])

(defn device-details-view []
  (let [dev (rf/subscribe [:selected-device])]
    (println @dev)
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
      [:div (map (partial service-card ((-> @dev :announcement :message :headers) "location"))
                 (-> @dev :dev :serviceList))]]]))

(defn ssdp-device-page [devid]
  [:div
   [:div.navbar.navbar-dark.bg-dark
    [:h4.text-white (str "SSDP Device")]]
   [device-details-view]])

(defn main-view []
  (let [active-view (rf/subscribe [:view])]
    [(fn render-main []
        (case @active-view
          :all-devices [ssdp-devices-page]
          :device [ssdp-device-page]))]))
