;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.parser
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [clojure.string :as str]
            [clojure.zip :as zip]
            [cljs.core.async :as async]
            [instaparse.core :as parser :refer-macros [defparser]]
            [tubax.core :as xml]
            [tubax.helpers :as xml-util]
            [taoensso.timbre :as log :include-macros true]))

(defonce ssdp-message-channel (atom nil))
(defonce ssdp-event-channel (atom nil))
(defonce ssdp-publisher (atom nil))

(defn descriptor-discriminator
  "Given a message from the descriptor channel, this function discriminates
  whether it is a device descriptor or service descriptor."
  [msg]
  (if (:service-info msg)
    :service
    :device))

(defonce descriptor-channel (atom nil))
(defonce descriptor-publisher (atom nil))

#_(defn start-ssdp-parser []
  (reset! ssdp-message-channel
          (async/chan 1
                      (comp (map ssdp-parse) (map ssdp-analyzer))
                      error-handler))
  (reset! ssdp-event-channel
          (async/chan 1
                      (comp (map event-parse) (map event-analyzer))
                      error-handler))
  (reset! ssdp-publisher
          (async/pub (async/merge [@ssdp-message-channel @ssdp-event-channel])
                     :type))
  (reset! descriptor-channel
          (async/chan 1
                      (comp (map descriptor-parse) (map analyze-descriptor))
                      error-handler))
  (reset! descriptor-publisher
          (async/pub @descriptor-channel descriptor-discriminator)))

#_(defn stop-ssdp-parser []
  (async/close! @ssdp-message-channel)
  (async/close! @ssdp-event-channel)
  (async/close! @descriptor-channel))
