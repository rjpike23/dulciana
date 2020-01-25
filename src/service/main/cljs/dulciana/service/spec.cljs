;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.spec
  (:require [clojure.test.check.generators]
            [cljs.spec.alpha :as spec]
            [cljs.spec.gen.alpha :as gen]))

(spec/def ::ssdp-msg-tag #{:SSDP_MSG})
(spec/def ::start-line-tag #{:START_LINE})
(spec/def ::msg-type-tag #{:NOTIFY :SEARCH :SUBSCRIBE :UNSUBSCRIBE})
(spec/def ::response-type-tag #{:RESPONSE})
(spec/def ::request-uri-tag #{:REQUEST_URI})
(spec/def ::value-tag #{:VALUE})
(spec/def ::headers-tag #{:HEADERS})
(spec/def ::header-tag #{:HEADER})
(spec/def ::name-tag #{:NAME})
(spec/def ::body-tag #{:BODY})


(spec/def ::ssdp-start-line (spec/cat :tag ::msg-type-tag
                                      :type-string string?
                                      :uri-tree (spec/spec
                                                 (spec/cat :tag ::request-uri-tag :uri string?))
                                      :http-ver-string string?))

(spec/def ::response-start-line (spec/cat :tag ::response-type-tag
                                          :text string?))

(spec/def ::start-line (spec/cat :tag ::start-line-tag
                                 :tree (spec/alt :response-msg (spec/spec ::response-start-line)
                                                 :ssdp-msg (spec/spec ::ssdp-start-line))))

(spec/def ::headers (spec/cat :tag ::headers-tag
                              :headers (spec/*
                                        (spec/spec
                                         (spec/cat :tag ::header-tag
                                                   :name (spec/spec (spec/cat :tag ::name-tag :name string?))
                                                   :value (spec/spec (spec/cat :tag ::value-tag :value string?)))))))

(spec/def ::body (spec/cat :tag ::body-tag
                           :body string?))


(spec/def ::ssdp-ast
  (spec/cat
   :tag ::ssdp-msg-tag
   :start-line (spec/spec ::start-line)
   :headers (spec/spec ::headers)
   :body (spec/spec ::body)))
