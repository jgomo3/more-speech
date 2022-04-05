(ns more-speech.nostr.protocol
  (:require [clojure.data.json :as json]
            [aleph.http :as a]
            [manifold.stream :as s]
            [clojure.core.async :as async]
            [more-speech.nostr.elliptic-signature :as ecc]
            )
  (:import (java.util Date)
           (java.text SimpleDateFormat)
           (java.nio.charset StandardCharsets)))

(def relays ["wss://nostr-pub.wellorder.net" ;*
             "wss://relayer.fiatjaf.com"
             "wss://nostr.rocks"
             "wss://nostr-relay.herokuapp.com"
             "wss://freedom-relay.herokuapp.com/ws" ;*
             "wss://nodestr-relay.dolu.dev/ws"
             "wss://nostrrr.bublina.eu.org"
             "wss://nostr-relay.freeberty.ne"
             "ws://nostr.rocks:7448" ;*
             "ws://nostr-pub.wellorder.net:7000"
             ])


(defn send-to [socket msg]
  (let [msg (json/write-str msg)]
    (println "sending:" msg)
    (s/put! socket msg)))

(defn format-time [time]
  (let [time (* time 1000)
        date (Date. (long time))]
    (.format (SimpleDateFormat. "MM/dd/yyyy kk:mm:ss z") date))
  )

(def name-list (atom {}))
(def messages (atom []))

(defn name-of [pubkey]
  (get @name-list pubkey pubkey))

(defn print-names []
  (doseq [entry @name-list]
    (prn entry)))

(defn wait-for-events
  ([conn]
   (wait-for-events conn messages))
  ([conn events]
   (let [terminator (async/chan)]
     (async/go
       (let [running? (atom true)]
         (while @running?
           (let [msg @(s/try-take! conn :drained 100 :timeout)]
             (Thread/sleep 1)
             (cond
               (some? (async/poll! terminator)) (reset! running? false)
               (= :drained msg) (reset! running? false)
               (= :timeout msg) nil
               :else (swap! events conj (json/read-str msg)))))
         (prn 'terminated)))
     terminator)))

(defn subscribe
  ([conn id]
   (subscribe conn id (int (- (/ (System/currentTimeMillis) 1000) 86400))))
  ([conn id since]
   (send-to conn ["REQ" id {"since" since}])))

(defn unsubscribe [conn id]
  (send-to conn ["CLOSE" id]))

(defn connect-to-relay [url]
  @(a/websocket-client
     url
     {:insecure? true}))

(defn make-text [msg private-key]
  (let [pub-key (ecc/pub-key private-key)
        created-at (quot (System/currentTimeMillis) 1000)
        id-event (json/write-str [0 (ecc/bytes->hex-string pub-key) created-at 1 [] msg])
        message-bytes (.getBytes id-event StandardCharsets/UTF_8)
        id (ecc/sha-256 message-bytes)
        event ["EVENT" {"id" (ecc/bytes->hex-string id)
                        "pubkey" (ecc/bytes->hex-string pub-key)
                        "created_at" created-at
                        "kind" 1
                        "tags" []
                        "content" msg
                        "sig" (ecc/bytes->hex-string (ecc/sign private-key id))
                        }]
        ]
    event))

(def private-key (ecc/sha-256 (.getBytes "I am Bob.")))

(defn get-events [events]
  (let [conn (connect-to-relay (get relays 0))
        id "more-speech"
        ]
    (subscribe conn id 1648848602)
    (let [terminator (wait-for-events conn events)]
      (Thread/sleep 10000)
      (async/put! terminator "bang!")
      )
    (Thread/sleep 100)
    (prn 'done)
    )
  )

;(defn -main
;  [& _args]
;  (let [conn (connect-to-relay "wss://nostr-pub.wellorder.net")
;        id "aleph-trial"
;        ]
;    (subscribe conn id 0)
;    (let [terminator (wait-for-events conn)]
;      (Thread/sleep 5000)
;      ;(s/put! conn "[\"EVENT\",{\"id\":\"c60fb1c88baceb9d7cfd84de8c7f92e1d4d6690e6cb65b97405e72a74acc5214\",\"pubkey\":\"2ef93f01cd2493e04235a6b87b10d3c4a74e2a7eb7c3caf168268f6af73314b5\",\"created_at\":1641914007,\"kind\":1,\"tags\":[],\"content\":\"test\",\"sig\":\"e5c4dfda7046f99469594f7bfd236f6e285be187aa7f604bff533636871d523f58ccb85169310a49b06a9499fd30d5a5806e4044e650cfd8eaef5b95597d1427\"}]")
;      (Thread/sleep 1000)
;      (async/put! terminator "bang!")
;      )
;    (Thread/sleep 100)
;    (prn 'done)
;    (spit "nostr-messages" @messages)
;    )
;  )

