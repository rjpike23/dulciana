(ns dulciana.client.db
  (:require [re-frame.core :as rf]))

(def initial-state
  {:remote {:devices {}
            :services {}}
   :ui {:active-view :all-devices}})
