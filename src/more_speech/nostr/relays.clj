(ns more-speech.nostr.relays
  (:require [clojure.spec.alpha :as s]
            [more-speech
             [config :as config]
             [relay :as relay]
             [websocket-relay :as ws-relay]]))

(defn- connection? [c]
  (= (::relay/type c) ::ws-relay/websocket))
(s/def ::read boolean?)
(s/def ::write boolean?)
(s/def ::subscribed boolean?)
(s/def ::connection (s/nilable connection?))
(s/def ::relay (s/keys :req-un [::read ::write ::subscribed ::connection]))
(s/def ::relays (s/map-of string? ::relay))

(def relays (atom nil))

(defn set-relay-defaults [relays]
  (loop [urls (keys relays)
         relays relays]
    (if (empty? urls)
      relays
      (let [url (first urls)
            relay (get relays url)
            relay (assoc relay :connection nil :subscribed false)]
        (recur (rest urls) (assoc relays url relay))))))

(defn load-relays [relay-text]
  (let [loaded-relays (if (empty? relay-text)
                        {}
                        (read-string relay-text))
        loaded-relays (set-relay-defaults loaded-relays)
        loaded-relays (if config/test-run?
                        {"wss://relay.damus.io" {:read true :write true}}
                        loaded-relays)
        ]
    (reset! relays loaded-relays)))

(defn load-relays-from-file [file-name]
  (load-relays (slurp file-name)))


(defn relays-for-writing []
  (loop [urls (keys @relays)
         relays-to-write {}]
    (if (empty? urls)
      relays-to-write
      (let [url (first urls)
            relay (get @relays url)
            read (:read relay)
            write (:write relay)]
        (recur (rest urls)
               (assoc relays-to-write url {:read read :write write}))))))

(defn validate-relay-url [url]
  (if (empty? url)
    nil
    (let [lurl (.trim (.toLowerCase url))]
      (if (and (re-matches config/url-pattern lurl)
               (or (.startsWith lurl "wss://")
                   (.startsWith lurl "ws://"))
               (= -1 (.indexOf lurl "localhost")))
        lurl
        nil)))
  )

(defn add-relay [url]
  (let [checked-url (validate-relay-url url)]
    (when (and (not (empty? url))
               (empty? checked-url))
      (prn 'invalid-relay url))
    (when (and (not (empty? checked-url))
               (not (contains? @relays checked-url)))
      (prn 'adding-relay checked-url)
      (swap! relays assoc checked-url {:read false :write false}))))

(defn add-recommended-relays-in-tags [event]
  (loop [tags (:tags event)]
    (if (empty? tags)
      nil
      (let [[tag-type _ url] (first tags)]
        (when (or (= tag-type :e) (= tag-type :p))
          (add-relay url)
          (recur (rest tags)))))))