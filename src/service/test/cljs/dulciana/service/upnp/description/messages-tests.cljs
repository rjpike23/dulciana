(ns ^:figwheel-always dulciana.service.upnp.description.messages-tests
  (:require [cljs.test :refer-macros [async deftest is testing run-tests]]
            [taoensso.timbre :as log :include-macros true]
            [dulciana.service.store :as store]
            [dulciana.service.upnp.description.core :as description]
            [dulciana.service.upnp.discovery.core :as discovery]
            [dulciana.service.upnp.description.messages :as messages]))

(def sample-service (store/map->service
                     {:action-list [(store/map->action
                                     {:name "name"
                                      :argument-list [(store/map->argument
                                                       {:direction "out"
                                                        :name "name"
                                                        :retval true
                                                        :related-state-variable "related-state-variable"})
                                                      (store/map->argument
                                                       {:direction "out"
                                                        :name "name2"
                                                        :related-state-variable "related-state-variable2"})]})]
                      :service-id "service-id"
                      :service-state-table [(store/map->service-state-variable
                                             {:allowed-value-list ["test1" "test2" "test3"]
                                              :allowed-value-range (store/map->allowed-value-range
                                                                    {:maximum "100"
                                                                     :minimum "0"
                                                                     :step "1"})
                                              :data-type "data-type"
                                              :default-value "default-value"
                                              :multicast "multicast"
                                              :name "name"
                                              :send-events "send-events"})]
                      :service-type "service-type"}))

(def scpd-result {:serviceStateTable
                  ({:allowedValueRange {:step "1", :maximum "100", :minimum "0"},
                    :defaultValue "default-value",
                    :dataType "data-type",
                    :name "name",
                    :multicast "multicast",
                    :sendEvents "send-events"}),
                  :actionList
                  ({:argumentList
                    ({:relatedStateVariable "related-state-variable2",
                      :direction "out",
                      :name "name2"}
                     {:retval true,
                      :relatedStateVariable "related-state-variable",
                      :direction "out",
                      :name "name"}),
                    :name "name"}), 
                  :specVersion {:minor "0", :major "2"}})

(def sample-device (store/map->device {:boot-id "boot-id"
                                           :config-id "config-id"
                                           :device-list "device-list"
                                           :device-type "device-type"
                                           :friendly-name "friendly-name"
                                           :icon-list [(store/map->icon
                                                        {:mime-type "mime-type"
                                                         :depth "depth"
                                                         :height "height"
                                                         :width "width"
                                                         :url "url"})]
                                           :manufacturer "manufacturer"
                                           :manufacturer-url "manufacturer-url"
                                           :model-description "model-description"
                                           :model-name "model-name"
                                           :model-url "model-url"
                                           :presentation-url "presentation-url"
                                           :serial-number "serial-number"
                                           :service-list [sample-service]
                                           :udn "udn"
                                           :upc "upc"
                                           :version "version"}))

(def dd-result {:device
             {:modelDescription "model-description",
              :serialNumber "serial-number",
              :iconList
              ({:url "url",
                :depth "depth",
                :height "height",
                :width "width",
                :mimetype "mime-type"}),
              :modelName "model-name",
              :friendlyName "friendly-name",
              :deviceType "device-type",
              :manufacturer "manufacturer",
              :serviceList
              ({:eventSubURL "/upnp/services/udn::service-type/control",
                :controlURL "/upnp/services/udn::service-type/eventing",
                :SCPDURL "/upnp/services/udn::service-type/scpd.xml",
                :serviceId "service-id",
                :serviceType "service-type"}),
              :UDN "udn"}, 
             :specVersion {:minor "0", :major "2"}})

(deftest dev-desc-test
  (is (= dd-result
         (messages/analyze-device-descriptor
          (tubax.core/xml->clj
           (messages/emit-device-descriptor sample-device))))))

(deftest scpd-test
  (is (= scpd-result
         (messages/analyze-service-descriptor
          (tubax.core/xml->clj
           (messages/emit-device-descriptor sample-service))))))
