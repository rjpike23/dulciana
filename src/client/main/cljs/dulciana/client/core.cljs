;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.


(ns dulciana.client.core
  (:require [cljss.core :as css :include-macros true]
            [ajax.core :as ajax]
            [devtools.core :as devtools]
            [reagent.core :as reagent]
            [re-frame.core :as rf]
            [secretary.core :as secretary :refer-macros [defroute]]
            [taoensso.sente :as sente]
            [taoensso.timbre :as log :include-macros true]
            [dulciana.client.events :as events]
            [dulciana.client.subs :as subs]
            [dulciana.client.views :as views]
            [dulciana.client.routes :as routes]
            [dulciana.client.ws :as ws]))

(enable-console-print!)
(devtools/install!)

(defn run []
  (ws/start-sente!)
  (secretary/dispatch! window.location.pathname)
  (rf/dispatch-sync [:initialize-db])
  (reagent/render (views/main-view)
                  (js/document.getElementById "app")))

(defn fig-reload-hook []
  (css/remove-styles!)
  (reagent/render (views/main-view)
                  (js/document.getElementById "app")))

(run)
