(ns dulciana.service.parser
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [clojure.string :as str]
            [clojure.zip :as zip]
            [cljs.core.async :as async]
            [instaparse.core :as parser :refer-macros [defparser]]
            [tubax.core :as xml]
            [tubax.helpers :as xml-util]
            [taoensso.timbre :as log :include-macros true]))

(defparser ssdp-parser
  (str/join "\n"
            '("SSDP_MSG = START_LINE CRLF HEADERS CRLF (BODY?)"
              "CRLF = '\\r\\n'"
              "SP = ' '"
              "URL = #'[^ ]*'"
              "START_LINE = NOTIFY | SEARCH | SUBSCRIBE | UNSUBSCRIBE | RESPONSE"
              "NOTIFY = 'NOTIFY' SP URL SP 'HTTP/1.1'"
              "SEARCH = 'M-SEARCH' SP URL SP 'HTTP/1.1'"
              "SUBSCRIBE = 'SUBSCRIBE' SP URL SP 'HTTP/1.1'"
              "UNSUBSCRIBE = 'UNSUBSCRIBE' SP URL SP 'HTTP/1.1'"
              "RESPONSE = 'HTTP/1.1 200 OK'"
              "HEADERS = (HEADER *)"
              "SEPERATOR = ':' #'\\s*'"
              "HEADER = (NAME SEPERATOR VALUE CRLF)"
              "NAME = #'[\\w\\-_]*'"
              "VALUE = #'[^\\r]*'"
              "BODY = #'.*'")))

(defn ssdp-parse [channel-msg]
  (let [parse-result (ssdp-parser (:message channel-msg))]
    (when (parser/failure? parse-result)
      (throw (js/Error. parse-result)))
    (assoc channel-msg :message parse-result)))

(defn error-handler [ex]
  (log/error ex))

(defn header-map [hdrs-ast]
  (into {} (map #(let [[HEADER [NAME name] _ [VALUE value]] %] [(str/lower-case name) value])
                hdrs-ast)))

(defn ssdp-analyzer [parse-result]
  (let [[SSDP_MSG [START_LINE [type]] _ [HEADERS & headers] _ body] (:message parse-result)]
    (assoc parse-result :message {:type type
                                  :headers (header-map headers)
                                  :body body})))

(defonce ssdp-message-channel (atom nil))
(defonce ssdp-publisher (atom nil))

(defn xml-parse [channel-msg]
  ;(log/debug channel-msg)
  (assoc channel-msg :message (xml/xml->clj (channel-msg :message))))

(defn xml-pair [node]
  [(:tag node) (xml-util/text node)])

(defn xml-map
  "Returns a function that converts an xml->clj data structure into a map {:<tag-name> <content>},
  according to the supplied spec. spec is a map from a tag keyword to a function of a single argument.
  When a child node with a tag name appearing in the spec map is found, the corresponding function is
  called with the node as argument. The return value is used as the <content> in the resulting map."
  [spec]
  (fn [node]
    (into {} (reduce (fn [out child]
                       (if-let [spec-fun (spec (child :tag))]
                         (cons [(child :tag) (spec-fun child)] out)
                         out))
                     '()
                     (node :content)))))

(defn xml-list [spec]
  (fn [node]
    (reduce (fn [out child]
              (if-let [spec-fun (spec (child :tag))]
                (cons (spec-fun child) out)
                out))
            '()
            (node :content))))

(defn analyze-device-descriptor [desc]
  ((xml-map
     {:specVersion (xml-map
                    {:major xml-util/text :minor xml-util/text})
      :device (xml-map
               {:deviceType xml-util/text
                :friendlyName xml-util/text
                :manufacturer xml-util/text
                :manufacturerURL xml-util/text
                :modelDescription xml-util/text
                :modelName xml-util/text
                :modelNumber xml-util/text
                :serialNumber xml-util/text
                :UDN xml-util/text
                :iconList (xml-list
                           {:icon (xml-map
                                   {:mimetype xml-util/text
                                    :width xml-util/text
                                    :height xml-util/text
                                    :depth xml-util/text
                                    :url xml-util/text})})
                :serviceList (xml-list
                              {:service (xml-map
                                         {:serviceId xml-util/text
                                          :serviceType xml-util/text
                                          :SCPDURL xml-util/text
                                          :controlURL xml-util/text
                                          :eventSubURL xml-util/text})})})})
   desc))

(defn analyze-service-descriptor [desc]
  ((xml-map {:specVersion (xml-map
                           {:major xml-util/text :minor xml-util/text})
             :actionList (xml-list
                          {:action (xml-map
                                    {:name xml-util/text
                                     :argumentList (xml-list
                                                    {:argument (xml-map
                                                                {:name xml-util/text
                                                                 :direction xml-util/text
                                                                 :relatedStateVariable xml-util/text
                                                                 :retval (constantly true)})})})})
             :serviceStateTable (xml-list
                                 {:stateVariable (xml-map
                                                  {:name xml-util/text
                                                   :datatype xml-util/text
                                                   :defaultValue xml-util/text
                                                   :allowedValueRange (xml-map
                                                                       {:minimum xml-util/text
                                                                        :maximum xml-util/text
                                                                        :step xml-util/text})
                                                   :allowedValueList (xml-list
                                                                      {:allowedValue xml-util/text})})})})
   desc))

(defn analyze-descriptor [channel-msg]
  (let [desc (:message channel-msg)]
    ;(log/debug desc)
    (assoc channel-msg
           :message (case (:tag desc)
                      :root (analyze-device-descriptor desc)
                      :scpd (analyze-service-descriptor desc)
                      desc))))

(defn descriptor-descriminator [msg]
  (if (:service-info msg)
    :service
    :device))

(defonce descriptor-channel (atom nil))
(defonce descriptor-publisher (atom nil))

(defn start-ssdp-parser []
  (reset! ssdp-message-channel (async/chan 1 (comp (map ssdp-parse) (map ssdp-analyzer)) error-handler))
  (reset! ssdp-publisher (async/pub @ssdp-message-channel (comp :type :message)))
  (reset! descriptor-channel (async/chan 1 (comp (map xml-parse) (map analyze-descriptor)) error-handler))
  (reset! descriptor-publisher (async/pub @descriptor-channel descriptor-descriminator)))

(defn stop-ssdp-parser []
  (async/close! @ssdp-message-channel)
  (async/close! @descriptor-channel))

;; Sample parse:
;[:SSDP_MSG [:START_LINE [:NOTIFY "NOTIFY" [:SP " "] [:URL "*"] [:SP " "] "HTTP/1.1"]] [:CRLF "\r\n"] [:HEADERS [:HEADER [:NAME "Host"] [:SEPERATOR ":" " "] [:VALUE "239.255.255.250:1900"] [:CRLF "\r\n"]] [:HEADER [:NAME "Cache-Control"] [:SEPERATOR ":" " "] [:VALUE "max-age=30"] [:CRLF "\r\n"]] [:HEADER [:NAME "Location"] [:SEPERATOR ":" " "] [:VALUE "http://192.168.0.1:1900/WFADevice.xml"] [:CRLF "\r\n"]] [:HEADER [:NAME "NTS"] [:SEPERATOR ":" " "] [:VALUE "ssdp:alive"] [:CRLF "\r\n"]] [:HEADER [:NAME "Server"] [:SEPERATOR ":" " "] [:VALUE "POSIX, UPnP/1.0 UPnP Stack/5.110.27.2006"] [:CRLF "\r\n"]] [:HEADER [:NAME "NT"] [:SEPERATOR ":" " "] [:VALUE "urn:schemas-wifialliance-org:service:WFAWLANConfig:1"] [:CRLF "\r\n"]] [:HEADER [:NAME "USN"] [:SEPERATOR ":" " "] [:VALUE "uuid:98408c8c-f5ce-9dbe-18bd-a8e5e6e180c0::urn:schemas-wifialliance-org:service:WFAWLANConfig:1"] [:CRLF "\r\n"]]] [:CRLF "\r\n"]]
