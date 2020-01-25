;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.client.routes
  (:require [re-frame.core :as rf]
            [secretary.core :as secretary :refer-macros [defroute]]
            [goog.events :as goog-events]
            [taoensso.timbre :as log :include-macros true])
  (:import [goog History]
           [goog.history Html5History EventType]))

(defonce +history+
  (doto (Html5History.)
    (.setUseFragment false)
    (goog-events/listen EventType.NAVIGATE #(secretary/dispatch! (str (.-token %))))
    (.setEnabled true)))

(defroute "/upnp/devices" [] (rf/dispatch [:view-devices]))
(defroute "/upnp/device/:devid/service/:svcid" [devid svcid] (rf/dispatch [:view-service devid svcid]))
(defroute "/upnp/device/:id" [id] (rf/dispatch [:view-device id]))

