(ns more-speech.config
  (:require [more-speech.mem :refer :all]
            [more-speech.db.in-memory :as in-memory]
            [more-speech.db.xtdb :as xtdb]))

(def default-font "COURIER-PLAIN-14")
(def bold-font "COURIER-BOLD-14")
(def small-font "COURIER-PLAIN-12")
(def small-bold-font "COURIER-BOLD-12")

(def article-width 120)

(def days-to-read 1) ;how far back in time to load old messages from the database.
(def days-to-read-messages-that-have-been-read 7)
(def read-contacts false) ;Read contacts from relays at startup.
(def read-contact-lists-days-ago 0.5)

(def migration-level 10)
(def version "2023-02-21T15:39")

(def subscription-id-base "ms")

(def test-run? (atom false))
(defn is-test-run? [] @test-run?)
(def test-relays {"wss://eden.nostr.land" {:read :read-all :write true}
                  "wss://relay.thes.ai" {:read :read-all :write true}
                  "wss://nostr-dev.wellorder.net" {:read :read-trusted, :write true}
                  "wss://nostr-pub.wellorder.net" {:read :read-trusted, :write true}
                  "wss://relay.damus.io" {:read :read-trusted :write true}
                  "wss://relay.nostr.info" {:read :read-all :write true}
                  "wss://relay.snort.social" {:read :read-trusted :write true}
                  "wss://nostr.mikedilger.com" {:read :read-all :write true}
                  })

(defn test-run! []
  (reset! test-run? true))

;---configuration files
(def private-directory (atom "private"))
(def migration-filename (atom "private/migration"))
(def nicknames-filename (atom "private/nicknames")) ;grandfathered.
(def profiles-filename (atom "private/profiles"))
(def keys-filename (atom "private/keys"))
(def relays-filename (atom "private/relays"))
(def read-event-ids-filename (atom "private/read-event-ids"))
(def tabs-filename (atom "private/tabs")) ;grandfathered.
(def tabs-list-filename (atom "private/tabs-list"))
(def messages-directory (atom "private/messages"))
(def messages-filename (atom "private/messages/message-file"))
(def user-configuration-filename (atom "private/user-configuration"))
(def contact-lists-filename (atom "private/ub-contacts"))

(def npub-reference #"npub1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+")
(def user-reference-pattern #"(?:\@[\w\-]+)|(?:npub1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)")
(def id-reference-pattern #"\@[0-9a-f]{64}")
(def hex-key-pattern #"[0-9a-f]{64}")
(def user-name-chars #"[\w\-]+")
(def reference-pattern #"\#\[\d+\]")

(def proof-of-work-default 16)

;; https://daringfireball.net/2010/07/improved_regex_for_matching_urls
(def url-pattern #"(?i)\b(?:(?:[a-z][\w-]+:(?:/{1,3}|[a-z0-9%])|www\d{0,3}[.]|[a-z0-9.\-]+[.][a-z]{2,4}/)(?:[^\s()<>]+|\(?:(?:[^\s()<>]+|(?:\(?:[^\s()<>]+\)))*\))+(?:\(?:(?:[^\s()<>]+|(?:\(?:[^\s()<>]+\)))*\)|[^\s`!()\[\]{};:'\".,<>?«»“”‘’]))")
(def relay-pattern #"ws+://[\w.-]+/?")
(def production-db :xtdb)
(def db-type (atom nil))

(defn set-db! [type] (reset! db-type type))

(defn get-db []
  (condp = @db-type
    :in-memory
    (in-memory/get-db)
    :xtdb
    (xtdb/get-db "prod-db")

    (throw (Exception. "No Database Specified"))))

