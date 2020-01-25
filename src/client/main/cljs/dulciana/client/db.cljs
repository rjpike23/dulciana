;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.client.db
  (:require [re-frame.core :as rf]))

(def initial-state
  {:remote {:devices {}
            :services {}}
   :ui {:active-view :all-devices
        :device {:selected-id nil}}})
