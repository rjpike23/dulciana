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
            [dulciana.service.upnp.discovery.core-tests :as discovery-tests]
            [dulciana.service.upnp.discovery.messages-tests :as discovery-msg-tests]
            [dulciana.service.upnp.description.messages-tests :as description-msg-tests]
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
