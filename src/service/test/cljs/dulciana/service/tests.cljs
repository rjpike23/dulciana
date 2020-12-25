;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns ^:figwheel-always dulciana.service.tests
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [clojure.string :as str]
            [taoensso.timbre :as log :include-macros true]
            [dulciana.service.spec :as dulc-spec]
            [dulciana.service.core :as core]
            [dulciana.service.events :as event]
            [dulciana.service.config :as config]
            [dulciana.service.store :as store]
            [dulciana.service.upnp.core :as upnp-core]
            [dulciana.service.upnp.control.core :as control]
            [dulciana.service.upnp.description.core :as desc]
            [dulciana.service.upnp.discovery.core :as disc]
            [dulciana.service.upnp.eventing.core :as eventing]
            [dulciana.service.upnp.discovery.core-tests :as discovery-tests]
            [dulciana.service.upnp.discovery.messages-tests :as discovery-msg-tests]
            [dulciana.service.upnp.description.messages-tests :as description-msg-tests]
            [dulciana.service.upnp.eventing.core-tests :as eventing-tests]
            [cljs.test :refer-macros [async deftest is testing run-all-tests]]
            [events :as node-events]))

(deftest event-listen
  (let [event-emitter (node-events/EventEmitter.)
        chans (event/listen* event-emitter ["foo" "bar"])]
    (async done
     (is event-emitter)
     (is (and (:foo chans) (:bar chans)))
     (go
       (let [m (async/<! (:foo chans))]
         (is (= event-emitter (first m)))
         (is (= '(1 2 3) (rest m))))
       (done))
     (.emit event-emitter "foo" 1 2 3)
     (doseq [c (vals chans)] (async/close! c)))))

(deftest slurp
  (let [event-emitter (node-events/EventEmitter.)
        out (async/chan)]
    (async done
           (event/slurp out event-emitter)
           (doseq [x ["a" "b" "c" "d"]]
             (.emit event-emitter "data" x))
           (.emit event-emitter "end")
           (go
             (is (= "abcd" (async/<! out)))
             (is (nil? (async/<! out)))
             (done)))))

(deftest slurp-error
  (let [event-emitter (node-events/EventEmitter.)
        out (async/chan)]
    (async done
           (event/slurp out event-emitter)
           (.emit event-emitter "data" "abcd")
           (.emit event-emitter "error" (js/Error. "Expected error."))
           (go
             (is (= js/Error (type (async/<! out))))
             (is (= "abcd" (async/<! out)))
             (is (nil? (async/<! out)))
             (done)))))

(deftest wrap-atom
  (let [a (atom {})
        w (event/wrap-atom a)
        c (async/chan)]
    (async/sub w :update c)
    (async done
           (reset! a {:test 1})
           (go
             (let [changes (async/<! c)]
               (is (not (nil? changes)))
               (is (= {:test 1} (:add changes)))
               (event/unwrap-atom a)
               (done))))))

(run-all-tests #"dulciana.*tests")

(def +test-service+ (store/map->service
                     {:action-list [(store/map->action
                                     {:argument-list [(store/map->argument
                                                       {:direction "out"
                                                        :name "state"
                                                        :retval true
                                                        :related-state-variable "stateVar"})]
                                      :name "increment"})
                                    (store/map->action
                                     {:argument-list [(store/map->argument
                                                       {:direction "out"
                                                        :name "state"
                                                        :retval true
                                                        :related-state-variable "stateVar"})]
                                      :name "read"})]
                      :service-id "test-service-id"
                      :service-state-table [(store/map->service-state-variable
                                             {:name "stateVar"
                                              :data-type "int"})]
                      :service-type "test-service-type"}))

(def +test-descriptor+ (store/map->device
                        {:boot-id 1
                         :config-id "123"
                         :device-list []
                         :device-type "abc"
                         :friendly-name "increment service"
                         :manufacturer "Dulciana"
                         :manufacturer-url "https://github.com/rjpike23/dulciana"
                         :model-name "Dulciana DLNA Server"
                         :model-url "http://huh.com"
                         :presentation-url "http://nowhere.none/"
                         :serial-number "000"
                         :service-list [+test-service+]
                         :udn "uuid:00000000-0000-0000-0000-000000000000"
                         :upc "upc"
                         :version "1.0"}))


(def +test-service-usn+ "uuid:00000000-0000-0000-0000-000000000000::test-service-type")

(def +test-service-state+ (atom {"state" 0}))

(defn atom-update-to-event [upd]
  {:update (:update upd)
   :usn +test-service-usn+})

(deftype test-device-type [descriptor state ^:mutable event-pub ^:mutable event-channel]
  store/upnp-device
  (get-descriptor [this] descriptor)
  (connect-pub-event-channel [this channel]
    (if (not (and event-pub event-channel))
      (do
        (set! event-pub (event/wrap-atom +test-service-state+))
        (set! event-channel (async/sub event-pub :update
                                       (async/chan 1 (map atom-update-to-event))))
        (async/pipe event-channel
                    channel false))))
  (disconnect-pub-event-channel [this]
    (event/unwrap-atom state)
    (async/close! event-channel)
    (set! event-pub nil)
    (set! event-channel nil))
  (invoke-action [this usn action-name args]
    (case action-name
      "increment" (swap! state update-in ["state"] inc)
      "read" {"state" (@state "state")})))

(def +test-device+ (->test-device-type +test-descriptor+ +test-service-state+ nil nil))

(defn register-test-device []
  (upnp-core/register-device +test-device+))

(defn deregister-test-device []
  (upnp-core/deregister-devices (:udn (store/get-descriptor +test-device+))))

(defn control-test-device []
  (store/invoke-action +test-device+ +test-service-usn+ "increment" {}))

(defn subscribe-test-device []
  (eventing/subscribe "uuid:00000000-0000-0000-0000-000000000000" "test-service-id"))

(defn unsubscribe-test-device []
  (let [subs (store/find-publications +test-service-usn+)]
    (doseq [s subs]
      (eventing/unsubscribe (:sid s)))))

(defonce +pub-sub-event-log-channel+ (atom nil))

(defn log-pub-sub-events []
  (reset! +pub-sub-event-log-channel+ (async/chan))
  (async/sub @eventing/+sub-event-pub+ :NOTIFY @+pub-sub-event-log-channel+)
  (event/channel-driver @+pub-sub-event-log-channel+
                        (fn [msg] (log/info "Pub-sub event recvd:" msg))))

(defn unlog-pub-sub-events []
  (async/unsub @eventing/+sub-event-pub+ :NOTIFY @+pub-sub-event-log-channel+))
