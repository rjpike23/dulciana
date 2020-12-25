;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns ^:figwheel-always dulciana.service.upnp.eventing.core-tests
  (:require [cljs.test :refer-macros [async deftest is testing run-tests]]
            [taoensso.timbre :as log :include-macros true]
            [dulciana.service.upnp.eventing.core :as eventing]))

(deftest xyz-test
  (is (= 1 1)))
