;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.upnp.discovery.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [clojure.string :as str]
            [taoensso.timbre :as log :include-macros true]
            [os :as os]
            [dgram :as dgram]
            [dulciana.service.config :as config]
            [dulciana.service.events :as events]
            [dulciana.service.upnp.discovery.messages :as messages]
            [dulciana.service.net :as net]
            [dulciana.service.parser :as parser]
            [dulciana.service.store :as store]))

(defonce +ssdp-announcement-flag+ (atom nil))

;;; SSDP module state vars:
;;; Map of open sockets:
(defonce +sockets+ (atom {}))

;;; Publication of SSDP messages
(defonce +ssdp-pub+ (atom nil))

;;; A queue of announcements to be sent by this server.
(defonce +announcement-queue+ (atom (async/chan)))

(defn expired? [now [key ann]]
  (< (:expiration ann) now))

(defn get-announced-device-ids
  ([]
   (get-announced-device-ids @store/+announcements+))
  ([announcement-map]
   (set (map (fn [[k v]] (store/get-dev-id k)) announcement-map))))

(defn get-announced-services-for-device
  ([dev-id]
   (get-announced-services-for-device dev-id @store/+announcements+))
  ([dev-id announcement-map]
   (set (map (fn [[k v]] k)
             (filter (fn [[k v]] (str/starts-with? k dev-id))
                     announcement-map)))))

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
  [socket message]
  (let [fam (.-family (.address socket))
        port (config/get-value :ssdp-mcast-port)
        address (config/get-value [:ssdp-mcast-addresses fam])]
    (log/trace "Sending SSDP message from" (.-address (.address socket)) "to" address ":" port)
    (.send socket message port address)))

(defmulti send-announcement (fn [msg iface] (:type msg)))

(defmethod send-announcement :search
  [msg iface]
  (send-ssdp-message iface (messages/emit-m-search-msg)))

(defmethod send-announcement :notify [msg iface]
  (let [{location :location, nt :nt, usn :usn} msg]
    (send-ssdp-message iface (messages/emit-notify-msg location "ssdp:alive" nt usn))))

(defmethod send-announcement :response [msg iface]
  (let [{location :location, st :st, usn :usn, server :server} msg]
    (send-ssdp-message iface (messages/emit-m-search-response-msg location server st usn))))

(defmethod send-announcement :goodbye [msg iface]
  (let [{nt :nt, usn :usn} msg]
    (send-ssdp-message iface (messages/emit-device-goodbye nt usn))))

(defn create-service-announcements [type udn loc service]
  (let [{service-type :service-type} service]
    {:type type
     :nt service-type
     :usn (store/create-usn udn service-type)
     :location loc}))

(defn create-device-announcements [type loc device]
  (let [{udn :udn, device-type :device-type, version :version} device]
    (concat (list
             {:type type
              :nt udn
              :usn udn
              :location loc}
             {:type type
              :nt (str device-type ":" version)
              :usn (str udn "::" device-type ":" version)
              :location loc})
            (map (partial create-service-announcements type udn loc) (:service-list device)))))

(defn create-root-device-announcements [type device]
  (when device
    (let [{udn :udn, device-type :device-type, version :version} device
          loc (str "/upnp/devices/" udn "/devDesc.xml")]
      (concat (list {:type type
                     :nt "upnp:rootdevice"
                     :usn (str udn "::upnp:rootdevice")
                     :location loc})
              (create-device-announcements type loc device)
              (flatten (map (partial create-device-announcements type loc) (:device-list device)))))))

(defn queue-device-announcements
  "Queues 3+2d+k announcements for a device with d embedded devices, and k services."
  [type device search-type]
  (let [announcements (create-root-device-announcements type device)]
    (async/go-loop [a announcements]
      (when (seq a)
        (when (first a)
          (async/>! @+announcement-queue+ (first a)))
        (recur (rest a))))))

(defn start-announcement-queue-processor []
  (reset! +ssdp-announcement-flag+ true)
  (async/go-loop []
    (let [ann (async/<! @+announcement-queue+)]
      (when ann
        (log/trace "Announcement" ann)
        (doseq [addr (keys @+sockets+)]
          (let [loc (str "http://" addr ":" (config/get-value :dulciana-upnp-server-port) (:location ann))]
            (send-announcement (assoc ann :location loc) (@+sockets+ addr))))))
    (async/<! (async/timeout (config/get-value :dulciana-upnp-throttle-interval)))
    (when @+ssdp-announcement-flag+
      (recur))))

(defn stop-announcement-queue-processor []
  (reset! +ssdp-announcement-flag+ false))

(defn start-notifications []
  (async/go-loop []
    (doseq [device (vals @store/+local-devices+)]
      (queue-device-announcements :notify (store/get-descriptor device) nil))
    (async/<! (async/timeout (config/get-value :dulciana-upnp-announcement-interval)))
    (if @+ssdp-announcement-flag+
      (recur)
      (doseq [device (vals @store/+local-devices+)]
        (queue-device-announcements :goodbye (store/get-descriptor device) nil)))))

(defn stop-notifications []
  (reset! +ssdp-announcement-flag+ false))

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
  (let [id (store/get-dev-id (-> notification :message :headers :usn))]
    (swap! announcements-atom
           (fn [anns]
             (into {} (filter (fn [[k v]] (not (device-member? id k)))
                              (remove-expired-items anns)))))))

(defn process-notification [notification]
  (let [notify-type (-> notification :message :headers :nts)]
    (log/trace "Rcvd" notify-type (-> notification :message :headers :location))
    (case notify-type
      "ssdp:alive" (update-announcements store/+announcements+ notification)
      "ssdp:update" (update-announcements store/+announcements+ notification)
      "ssdp:byebye" (remove-announcements store/+announcements+ notification)
      (log/warn "Ignoring announcement type" notify-type))))

(defn process-ssdp-response [response]
  (update-announcements store/+announcements+ response))

(defn start-listener
  "Starts an SSDP listener on the specified interface. Returns a map with two entries,
  :socket and :channels. :socket is the underlying UDP socket from Node.JS, :channels
  is a map of core.async channels, set to receive events from the UDP socket. Three
  of the channels, :close, :error and :listening are only expected to ever see one
  message. These messages are handled internally, and state updated correspondingly.
  Adding additional consumers to :close, :error or :listening channels may cause problems.
  The :message channel is returned without a consumer. An external consumer must be
  configured by the caller or UDP messages will be dropped."
  [[name iface]]
  (let [{:keys [family address]} iface
        {:keys [socket channels] :as sock} (net/create-udp-socket (config/get-value [:ssdp-protocols family]))]
    (async/take! (:close channels)
                 (fn [[this]]
                   (log/debug "Socket closed" (:address iface))
                   (events/close* channels)
                   (swap! +sockets+ dissoc (:address iface))))
    (async/take! (:error channels)
                 (fn [[this err :as msg]]
                   (when msg
                     (log/error err "Socket error on" (:address iface))
                     (.close socket))))
    (async/take! (:listening channels)
                 (fn [[this :as msg]]
                   (when msg
                     (log/debug "Socket established" (:address iface))
                     (try
                       (net/add-membership sock
                                           (config/get-value [:ssdp-mcast-addresses family]) address)
                       (swap! +sockets+ assoc address socket)
                       (send-announcement {:type :search} socket)
                       (catch :default err
                         (log/error err "Error joining multicast group on" address))))))
    (net/bind-udp-socket sock (config/get-value :ssdp-mcast-port) address)
    sock))

(defn create-message-map [[sock msg rinfo iface]]
  {:message (.toString msg)
   :local iface
   :remote (js->clj rinfo :keywordize-keys true)
   :timestamp (js/Date.)})

(defn start-listeners []
  (let [ssdp-chan (async/chan 1 (comp (map create-message-map)
                                      (map messages/ssdp-parse)
                                      (map messages/ssdp-analyzer)))]
    (async/pipe (async/merge (map (fn [iface]
                                    (let [{:keys [socket channels]} (start-listener iface)]
                                      (async/pipe (:message channels)
                                                  (async/chan 1 (map #(conj % iface))))))
                                  (filter (fn [[k v]] v)
                                          (net/get-ifaces))))
                ssdp-chan)
    (reset! +ssdp-pub+ (async/pub ssdp-chan :type)))
  (events/channel-driver (async/sub @+ssdp-pub+ :NOTIFY (async/chan)) process-notification)
  (events/channel-driver (async/sub @+ssdp-pub+ :RESPONSE (async/chan)) process-ssdp-response))

(defn stop-listeners
  ([]
   (stop-listeners @+sockets+))
  ([sockets]
   (doseq [[k socket] sockets]
     (.close socket))))
