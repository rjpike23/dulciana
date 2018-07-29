;  Copyright 2017-2018, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.ssdp.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [clojure.string :as str]
            [taoensso.timbre :as log :include-macros true]
            [os :as os]
            [dgram :as dgram]
            [dulciana.service.events :as events]
            [dulciana.service.ssdp.messages :as messages]
            [dulciana.service.parser :as parser]
            [dulciana.service.net :as net]))

;;; Define SSDP protocol networking constants:
(def *ssdp-mcast-addresses* {"IPv4" "239.255.255.250"
                             "IPv6" "ff0e::c"})
(def *ssdp-protocols* {"IPv4" "udp4"
                       "IPv6" "udp6"})
(def *ssdp-port* 1900)

;;; SSDP module state vars:
;;; Map of open sockets:
(defonce *sockets* (atom {}))
;;; Publication of SSDP messages
(defonce *ssdp-pub* (atom nil))

(defonce *announcements* (atom {}))

(defonce *announcements-pub* (events/wrap-atom *announcements*))

(defn create-usn
  "Constructs a USN from the device id and service id."
  [dev-id svc-id]
  (if svc-id
    (str dev-id "::" svc-id)
    dev-id))

(defn get-dev-id
  "Utility function to extract the device id from a USN value."
  [usn]
  (first (str/split usn "::")))

(defn get-svc-id
  "Utility function extract the service id from a USN value."
  [usn]
  (second (str/split usn "::")))

(defn expired? [now [key ann]]
  (< (:expiration ann) now))

(defn get-announced-device-ids [announcement-map]
  (set (map (fn [[k v]] (get-dev-id k)) announcement-map)))

(defn get-announced-services-for-device [dev-id announcement-map]
  (set (map (fn [[k v]] k)
            (filter (fn [[k v]] (str/starts-with? k dev-id))
                    announcement-map))))

(defn find-announcement [dev-id]
  (some (fn [[k v]] (when (str/starts-with? k dev-id) v))
        @*announcements*))

(defn device-member?
  "Utility function to determine if a service with the supplied
  USN (svcname) is a part of the device with the supplied id."
  [devid svcname]
  (str/starts-with? svcname devid))

;;; Multicast message sender:
(defn send-ssdp-message
  "Sends a multicast message from the supplied interface.
  A socket for the interface should have previously
  been opened with start-listener. See nodejs dgram.send()."
  [iface message]
  (log/trace "Sending SSDP message" message)
  (when-let [socket (@*sockets* (iface :address))]
    (.send socket message *ssdp-port* (*ssdp-mcast-addresses* (iface :family)))))

(defn send-ssdp-search-message
  "Sends a M-SEARCH SSDP Discovery message on all enabled interfaces."
  [iface]
  (send-ssdp-message iface (messages/emit-m-search-msg)))

(defn remove-expired-items [items]
  (into {} (filter (comp not (partial expired? (js/Date.))) items)))

(defn update-announcements [announcements-atom notification]
  (swap! announcements-atom
         (fn [anns]
           (assoc (remove-expired-items anns)
                  (-> notification :message :headers :usn) notification))))

(defn remove-announcements
  "Remove all announcements from state that have the same device id as the
  supplied notification."
  [announcements-atom notification]
  (let [id (get-dev-id (-> notification :message :headers :usn))]
    (swap! announcements-atom
           (fn [anns]
             (into {} (filter (fn [[k v]] (not (device-member? id k)))
                              (remove-expired-items anns)))))))

(defn process-notification [notification]
  (let [notify-type (-> notification :message :headers :nts)]
    (case notify-type
      "ssdp:alive" (update-announcements *announcements* notification)
      "ssdp:update" (update-announcements *announcements* notification)
      "ssdp:byebye" (remove-announcements *announcements* notification)
      (log/warn "Ignoring announcement type" notify-type))))

(defn start-listener
  "Starts an SSDP listener on the specified interface. Returns a map with two entries,
  :socket and :channels. :socket is the underlying UDP socket from Node.JS, :channels
  is a map of core.async channels, set to receive events from the UDP socket. Three
  of the channels, :close, :error and :listening are only expected to ever see one
  message. These messages are handled internally, and state updated correspondingly.
  Adding additional consumers to :close, :error or :listening channels may cause problems.
  The :message channel is returned without a consumer. An external consumer must be
  configured by the caller or UDP messages will be dropped."
  [iface]
  (let [{:keys [socket channels] :as sock} (net/create-udp-socket (*ssdp-protocols* (:family iface)))]
    (async/take! (:close channels)
                 (fn [[this]]
                   (log/debug "Socket closed" (:address iface))
                   (events/close* channels)
                   (swap! *sockets* dissoc (:address iface))))
    (async/take! (:error channels)
                 (fn [[this err :as msg]]
                   (when msg
                     (log/error err "Socket error on" (:address iface))
                     (.close socket))))
    (async/take! (:listening channels)
                 (fn [[this :as msg]]
                   (when msg
                     (log/debug "UDP Socket established")
                     (net/add-membership sock (*ssdp-mcast-addresses* (:family iface)) (:address iface))
                     (swap! *sockets* assoc (:address iface) socket)
                     (send-ssdp-search-message iface))))
    (net/bind-udp-socket sock *ssdp-port* (:address iface))
    sock))

(defn handle-message [[sock msg rinfo]]
  {:message (.toString msg)
   :local (js->clj (.address sock) :keywordize-keys true)
   :remote (js->clj rinfo :keywordize-keys true)
   :timestamp (js/Date.)})

(defn start-listeners []
  (let [ssdp-chan (async/chan 1 (comp (map handle-message)
                                      (map messages/ssdp-parse)
                                      (map messages/ssdp-analyzer)))]
    (async/pipe (async/merge (map #(let [{:keys [socket channels]} (start-listener %)]
                                     (:message channels))
                                  (net/get-ifaces)))
                ssdp-chan)
    (reset! *ssdp-pub* (async/pub ssdp-chan :type))
    (events/channel-driver (async/sub @*ssdp-pub* :NOTIFY (async/chan)) process-notification)))

(defn stop-listeners [sockets]
  (doseq [[k socket] sockets]
    (.close socket)))

