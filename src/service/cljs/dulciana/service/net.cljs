;;;; Copyright 2017, Radcliffe J. Pike. All rights reserved.

(ns dulciana.service.net
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [>!]]
            [ajax.core :as ajax]
            [taoensso.timbre :as log :include-macros true]
            [dulciana.service.parser :as parser]
            [dulciana.service.messages :as messages]))

(def os (nodejs/require "os"))
(def dgram (nodejs/require "dgram"))
(def url (nodejs/require "url"))

;;; Define SSDP protocol networking constants:
(def ssdp-mcast-addresses {"IPv4" "239.255.255.250"
                           "IPv6" "ff0e::c"})
(def ssdp-protocols {"IPv4" "udp4"
                     "IPv6" "udp6"})
(def ssdp-port 1900)

;;; Networking module state vars:
(defonce sockets (atom {}))

(defn get-ifaces
  "Returns a list of active external network interfaces. See nodejs os.networkInterfaces()."
  []
  (filter #(not (% :internal))
          (flatten (map (fn [[k v]] v) (js->clj (os.networkInterfaces) :keywordize-keys true)))))

;;; Multicast message sender:
(defn send-ssdp-message
  "Sends a multicast message from the supplied interface. A socket for the interface should have previously
  been opened with net/start-listener. See nodejs dgram.send()."
  [iface message]
  (when-let [socket (@sockets (iface :address))]
    (.send socket message ssdp-port (ssdp-mcast-addresses (iface :family)))))

;;; Datagram event handlers follow:
(defn handle-ssdp-error
  "Callback for socket errors returned from NodeJS datagram API."
  [iface socket exception]
  (log/error "Socket error!" exception)
  (.close socket))

(defn handle-ssdp-message [iface socket msg remote-js]
  "Callback for messages received on the SSDP multicast socket.
  pushes messages onto the ssdp-message-channel, with metadata."
  (let [remote (js->clj remote-js :keywordize-keys true)]
    ;(log/debug "Socket message received from" (remote :address) "on" (iface :address))
    (go (>! @parser/ssdp-message-channel {:remote remote
                                          :interface iface
                                          :timestamp (js/Date.)
                                          :message (.toString msg)}))))

(defn handle-ssdp-socket-close
  "Callback for socket close events generated from NodeJS datagram API."
  [iface socket]
  (log/debug "Socket closed")
  (swap! sockets dissoc (iface :address)))

(defn handle-ssdp-socket-listening
  "Callback for socket listening events generated from NodeJS datagram API.
  This implementation adds the socket to the SSDP multicast group, and
  updates the sockets var to include the new socket."
  [iface socket]
  (log/debug "Socket listening, adding iface to multicast group" (iface :address))
  (.addMembership socket
                  (ssdp-mcast-addresses (iface :family))
                  (iface :address))
  (swap! sockets assoc (iface :address) socket))

;;; HTTP methods for sending requests for descriptors / SOAP requests below:
(defn get-device-descriptor [device-announcement]
  ;(log/debug "Getting descriptor for:" device-announcement)
  (ajax/GET ((-> device-announcement :message :headers) "location")
            {:handler #(go (>! @parser/descriptor-channel {:announcement device-announcement
                                                           :timestamp (js/Date.)
                                                           :error false
                                                           :message %}))
             :error-handler #(go (>! @parser/descriptor-channel {:announcement device-announcement
                                                                 :timestamp (js/Date.)
                                                                 :error true
                                                                 :message %}))}))

(defn get-service-descriptor [device-announcement service-info]
  (let [scpdurl (url.resolve ((-> device-announcement :message :headers) "location") (service-info :SCPDURL))]
    (ajax/GET scpdurl
              {:handler #(go (>! @parser/descriptor-channel {:announcement device-announcement
                                                             :service-info service-info
                                                             :timestamp (js/Date.)
                                                             :error false
                                                             :message %}))
               :error-heandler #(go (>! @parser/descriptor-channel {:announcement device-announcement
                                                                    :service-info service-info
                                                                    :timestamp (js/Date.)
                                                                    :error true
                                                                    :message %}))})))

(defn send-control-request [url service-type action-name params]
  (let [msg (messages/emit-control-soap-msg service-type action-name params)
        hdrs {"USER-AGENT" "Unix/5.0 UPnP/2.0 dulciana/1.0"
              "SOAPACTION" (str "\"" service-type "#" action-name "\"")}]
    (ajax.core/POST url {:body msg
                         :headers hdrs})))

;;; Following functions are used to init/teardown the SSDP multicast listeners.
(defn start-listener
  "Creates a new multicast socket on the supplied interface."
  [iface]
  (let [socket (dgram.createSocket (ssdp-protocols (iface :family)))]
    (.on socket "error" (partial handle-ssdp-error iface socket))
    (.on socket "message" (partial handle-ssdp-message iface socket))
    (.on socket "close" (partial handle-ssdp-socket-close iface socket))
    (.on socket "listening" (partial handle-ssdp-socket-listening iface socket))
    (.bind socket ssdp-port (iface :address))
    socket))

(defn start-listeners []
  (doseq [iface (get-ifaces)]
    (start-listener iface)))

(defn stop-listeners []
  (doseq [[k socket] @sockets] (.close socket)))
