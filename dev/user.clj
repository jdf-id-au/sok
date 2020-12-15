(ns user
  (:require [sok.api :as sok]
            [clojure.core.async :as async :refer [chan go >! <! >!! <!!]]))

#_ (def server (sok/server! 8123))
; Connect in web browser then:
#_ (-> server :clients deref keys first)

#_ (<!! (:in server))
#_ (def id (first *1))
#_ (async/put! (:out server) [id "oopsie"])
#_ (async/put! (server :out) [id (->> (range 100000) (interpose \ ) (apply str))])

#_ ((server :close))

#_ (def client (sok/client! "ws://localhost:8123"))

; Arrgh Chrome seems to divide large outgoing messages into frames but Firefox doesn't (and closes ws)...