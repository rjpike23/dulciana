(ns dulciana.service.store
  (:require [taoensso.timbre :as log :include-macros true]
            [dulciana.service.config :as config]
            [dulciana.service.events :as events]))

(defonce *subscriptions* (atom {}))

;;; A map of all received announcements.
(defonce *announcements* (atom {}))

;;; A core.async/pub of updates to the *announcements* atom.
(defonce *announcements-pub* (events/wrap-atom *announcements*))

(defonce *remote-devices* (atom {}))

(defonce *remote-devices-pub* (events/wrap-atom *remote-devices*))

(defonce *remote-services* (atom {}))

(defonce *remote-services-pub* (events/wrap-atom *remote-services*))

(defonce *local-devices* (atom (config/get-value :dulciana-init-local-devices)))

