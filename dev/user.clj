(ns user
  (:require [sok.api :as sok]
            [clojure.core.async :as async :refer [chan go >! <! >!! <!!]]
            [hato.websocket :as ws]))

(def server (sok/server! 8123))

#_ ((:close server))

#_ (def w @(ws/websocket "ws://localhost:8123"
             ; TODO need to concat strings
             {:on-message (fn [ws msg last?] (println "client received" (if last? "(last)") msg))
              :on-close (fn [ws status reason] (println "client ws closed" status reason))}))

#_ (ws/send! w #_"hi there" (apply str (interpose \space (range 10000))))
#_ (ws/close! w)

#_ (<!! (:in server))
#_ (def id (first *1))
#_ (async/put! (:out server) [id "oopsie"])