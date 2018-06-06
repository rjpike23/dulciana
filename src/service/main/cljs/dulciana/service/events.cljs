;  Copyright 2017-2018, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.events
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [taoensso.timbre :as log :include-macros true]
            [events :as node-events]))

(defn listen
  ([src event] (listen src event (async/chan)))
  ([src event channel] (.on src (name event)
                            (fn ([] (async/put! channel [src]))
                              ([& args] (async/put! channel
                                                    (apply vector src args)))))
   channel))


(defn listen*
  "Registers event listeners for the events specificed in the events
  array on the supplied src. Returns a map of event names to channels.
  When the specified event occurs on the src object, the arguments of
  the event are put on the corresponding channel. If the second argument
  is a map, the keys are treated as names of events and the value is
  an existing channel that will receive the event."
  ([src events]
   (if (sequential? events)
     (into {} (map (fn [evt]
                     [(keyword evt) (listen src evt)])
                   events))
     (do (doseq [[evt channel] events]
           (listen src evt channel))
         events))))

(defn close* [channels]
  (doseq [c (vals channels)]
    (async/close! c)))

(defn channel-driver [chan handler]
  (go-loop []
    (let [item (async/<! chan)]
      (when item
        (try
          (handler item)
          (catch :default e
            (log/error e)))
        (recur)))))

(defn slurp [out-chan node-stream]
  (let [channels (listen* node-stream [:data :end :error])]
    (async/take! (:end channels)
                 #(close* channels))
    (async/take! (:error channels)
                 (fn [[this err]]
                   (close* channels)
                   (if err
                     (async/put! out-chan err))))
    (async/pipe (async/reduce (fn [arg1 [this arg2]]
                                (apply str arg1 arg2))
                              ""
                              (:data channels))
                out-chan)))

(defn pump [c e n]
  (async/go-loop []
    (let [x (async/<! c)]
      (when x
        (apply #(.emit e) n x)
        (recur)))))

(defn event-emitter
  ([channels]
   (event-emitter channels (node-events/EventEmitter.)))
  ([channels e]
   (doseq [[k v] channels]
     (pump v e k))))

