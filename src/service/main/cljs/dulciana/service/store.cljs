(ns dulciana.service.store
  (:require [taoensso.timbre :as log :include-macros true]
            [dulciana.service.config :as config]
            [dulciana.service.events :as events]))

(defrecord argument
    [direction
     name
     retval
     related-state-variable])

(defrecord action
    [argument-list
     name])

(defrecord allowed-value-range
    [maximum
     minimum
     step])

(defrecord service-state-variable
    [allowed-value-list
     allowed-value-range
     data-type
     default-value
     multicast
     name
     send-events])

(defrecord service
    [action-list
     service-id
     service-state-table
     service-type])

(defrecord icon
    [mime-type
     depth
     height
     width
     url])

(defrecord device
    [boot-id
     config-id
     device-list
     device-type
     friendly-name
     icon-list
     manufacturer
     manufacturer-url
     model-description
     model-name
     model-url
     presentation-url
     serial-number
     service-list
     udn
     upc
     version])

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

