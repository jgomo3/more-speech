(ns more-speech.ui.formatters
  (:require [clojure.string :as string]
            [more-speech.bech32 :as bech32]
            [more-speech.config :as config :refer [get-db]]
            [more-speech.db.gateway :as gateway]
            [more-speech.logger.default :refer [log-pr]]
            [more-speech.mem :refer :all]
            [more-speech.nostr.contact-list :as contact-list]
            [more-speech.nostr.events :as events]
            [more-speech.nostr.util :as util :refer [hexify]]
            [more-speech.ui.formatter-util :refer :all]))

(defn format-user-id
  ([user-id]
   (format-user-id user-id 20 10))

  ([user-id length]
   (format-user-id user-id length 10))

  ([user-id length id-length]
   (if (nil? user-id)
     ""
     (let [trusted? (contact-list/is-trusted? user-id)
           trusted-by (contact-list/which-contact-trusts user-id)
           petname (contact-list/get-petname user-id)
           id-string (abbreviate (util/num32->hex-string user-id) id-length)
           profile (gateway/get-profile (get-db) user-id)
           profile-name (get profile :name id-string)]
       (cond
         (seq petname)
         (abbreviate petname length)

         trusted?
         (abbreviate profile-name length)

         (some? trusted-by)
         (let [trusted-id-string (abbreviate (util/num32->hex-string trusted-by) id-length)
               trusted-profile (gateway/get-profile (get-db) trusted-by)
               trusted-profile-name (get trusted-profile :name trusted-id-string)
               trusted-pet-name (contact-list/get-petname trusted-by)
               trusted-name (if (seq trusted-pet-name) trusted-pet-name trusted-profile-name)]
           (abbreviate (str profile-name "<-" trusted-name) length))

         :else
         (str "(" (abbreviate profile-name (- length 2)) ")"))))))

(defn name-exists? [name]
  (if (empty? name) nil name))

(defn get-best-name [id]
  (or (name-exists? (contact-list/get-petname id))
      (name-exists? (:name (gateway/get-profile (get-db) id)))
      (hexify id)))

(defn lookup-reference [tags reference]
  (let [ref-string (re-find #"\d+" reference)
        index (Integer/parseInt ref-string)]
    (if (>= index (count tags))
      reference
      (try
        (let [tag-type (-> tags (nth index) first)]
          (if (or (= :p tag-type)
                  (= :e tag-type))
            (let [id-string (-> tags (nth index) second)
                  id (util/hex-string->num id-string)
                  name (get-best-name id)]
              (str "@" name))
            reference))
        (catch Exception e
          (log-pr 1 'lookup-reference 'bad-id index tags)
          (log-pr 1 (.getMessage e))
          "@-unknown-")))))

(defn get-author-name [npub]
  (try
    (let [id (bech32/address->number npub)
          profile (gateway/get-profile (get-db) id)
          petname (contact-list/get-petname id)]
      (cond
        (some? petname) petname
        (some? profile) (:name profile)
        :else npub))
    (catch Exception e
      (log-pr 2 'get-author-name (.getMessage e))
      npub)))

(defn replace-nostr-references [s]
  (let [padded-content (str " " s " ")
        references (re-seq config/nostr-npub-reference-pattern padded-content)
        references (mapv second references)
        references (mapv get-author-name references)
        references (mapv #(str "nostr:" %) references)
        segments (string/split padded-content config/nostr-npub-reference-pattern)
        referents (conj references " ")
        replaced-content (string/trim (apply str (interleave segments referents)))]
    replaced-content)
  )

(defn replace-references
  ([event]
   (replace-references (:content event) (:tags event)))

  ([content tags]
   (let [padded-content (str " " content " ")
         references (re-seq config/reference-pattern padded-content)
         segments (string/split padded-content config/reference-pattern)
         referents (mapv (partial lookup-reference tags) references)
         referents (conj referents " ")
         replaced-content (string/trim (apply str (interleave segments referents)))]
     replaced-content)))

(defn get-subject [tags]
  (if (empty? tags)
    nil
    (let [tag (first tags)]
      (if (= (first tag) :subject)
        (abbreviate (second tag) 90)
        (recur (rest tags))))))

(defn make-reaction-mark [event]
  (let [reactions (count (:reactions event))]
    (cond (> reactions 99) ">>"
          (zero? reactions) "  "
          :else (format "%2d" reactions))))

(defn make-dm-mark [event]
  (let [tags (:tags event)
        ptags (filter #(= :p (first %)) tags)
        to (util/unhexify (second (first ptags)))
        to-name (if (= to (get-mem :pubkey))
                  ""
                  (str "-> " (format-user-id to)))]
    (str "🚫" to-name " ")))

(defn format-header
  ([event]
   (format-header event :long))

  ([{:keys [pubkey created-at tags zaps] :as event} opt]
   (if (nil? event)
     "nil"
     (let [format-spec (if (= opt :long)
                         "%s%s %20s %s %s%s%s\n"
                         "%s%s %s %s %s%s%s")
           content (replace-references event)
           content (replace-nostr-references content)
           name (format-user-id pubkey)
           time (format-time created-at)
           subject (get-subject tags)
           [reply-id _ _] (events/get-references event)
           reply-mark (if (some? reply-id) "^" " ")
           dm-mark (if (= 4 (:kind event)) (make-dm-mark event) "")
           zap-mark (if (some? zaps) "❗⚡ " "")
           reaction-mark (make-reaction-mark event)
           header-text (-> content (string/replace \newline \~) (abbreviate 130))
           content (if (empty? subject)
                     header-text
                     (abbreviate (str subject "|" header-text) 130))]
       (format format-spec reply-mark reaction-mark name time zap-mark dm-mark content)))))

(defn make-cc-list [event]
  (let [p-tags (events/get-tag event :p)
        hex-ids (map first p-tags)
        ids (map util/unhexify hex-ids)
        names (map get-best-name ids)
        cc-items (set (map #(format "CC: @%s\n" %) names))]
    (apply str cc-items)))

(defn format-reply [{:keys [content tags] :as event}]
  (let [content (replace-references content tags)
        content (prepend> content)
        header (format "From: %s at %s on %s\n%s"
                       (format-user-id (:pubkey event))
                       (format-time (:created-at event))
                       (first (:relays event))
                       (make-cc-list event)
                       )
        dm-prefix (if (:dm event)
                    (str "D @" (get-best-name (:pubkey event)) "\n")
                    "")]
    (str dm-prefix header ">---------------\n" content)))

(defn html-escape [content]
  (string/escape content {\& "&amp;"
                          \< "&lt;"
                          \> "&gt;"
                          \" "&quot;"
                          \' "&#x27;"
                          \/ "&#x2F;"}))

(defn break-newlines [content]
  (string/replace content "\n" "<br>"))

(defn non-breaking-spaces [s]
  (let [space-index (.indexOf s "  ")]
    (if (neg? space-index)
      s
      (str (subs s 0 space-index)
           "&nbsp"
           (non-breaking-spaces (subs s (+ space-index 1)))))))

(defn format-replies [content]
  (string/replace content " >" "\n>"))

(defn linkify [url]
  (let [split-url (string/split url #"://")
        uri (if (= 2 (count split-url)) (second split-url) url)]
    (str "<a href=\"" url "\">" uri "</a>")))

(defn ms-linkify [type subject link-text]
  (str "<a href=\"" (str type "://" subject) "\">" link-text "</a>"))

(defn img-ify [seg]
  (str "<a href=\"" seg "\"><img src=\"" seg "\"></a><br>" (linkify seg)))

(defn combine-patterns
  "patterns are a list of [:name pattern]"
  [& patterns]
  (let [grouped-patterns (map #(str "(?<" (name (first %)) ">" (second %) ")") patterns)
        combined-patterns (interpose "|" grouped-patterns)]
    (re-pattern (apply str combined-patterns))))


(defn alter-segment-type [type segment]
  (if-not (= type :url)
    type
    (if (or (.endsWith segment ".jpg")
            (.endsWith segment ".jpeg")
            (.endsWith segment ".gif")
            (.endsWith segment ".png")
            ) :img :url)))

(defn segment-article
  ([content]
   (segment-article content []))

  ([content segments]
   (let [patterns [[:nostrnotereference config/nostr-note-reference-pattern]
                   [:nostreventreference config/nostr-event-reference-pattern]
                   [:nostrnpubreference config/nostr-npub-reference-pattern]
                   ;[:nostrprofilereference config/nostr-profile-reference-pattern]
                   [:nostrnamereference config/nostr-name-reference-pattern]
                   [:idreference config/id-reference-pattern]
                   [:namereference config/user-reference-pattern]
                   [:url config/url-pattern]]
         pattern (apply combine-patterns patterns)
         group-names (map first patterns)]
     (loop [content content
            segments segments]
       (let [matcher (re-matcher pattern content)
             segment (first (re-find matcher))]
         (cond
           (empty? content)
           segments

           (some? segment)
           (let [grouped-by-name (map #(vector (keyword %) (.group matcher (name %))) group-names)
                 the-group (filter #(some? (second %)) grouped-by-name)
                 segment-type (ffirst the-group)
                 url-start-index (string/index-of content segment)
                 url-end-index (+ url-start-index (.length segment))
                 text-sub (subs content 0 url-start-index)
                 url-sub (subs content url-start-index url-end-index)
                 rest (subs content url-end-index)
                 segment-type (alter-segment-type segment-type url-sub)]
             (recur rest
                    (concat segments
                            (if (empty? text-sub)
                              [[segment-type url-sub]]
                              [[:text text-sub] [segment-type url-sub]]))))
           :else
           (concat segments (list [:text content]))))))))

(defn extract-reference [s]
  (cond
    (.startsWith s "nostr:") (subs s 6)
    (.startsWith s "@") (subs s 1)
    :else s))

(defn reformat-article-into-html [article]
  (let [segments (segment-article article)]
    (reduce
      (fn [formatted-content [seg-type seg]]
        (let [reference (extract-reference seg)]
          (cond
            (= seg-type :text)
            (str formatted-content
                 ((comp
                    non-breaking-spaces
                    break-newlines
                    html-escape
                    format-replies
                    ) seg)
                 )

            (= seg-type :url)
            (str formatted-content (linkify seg))

            (= seg-type :namereference)
            (str formatted-content (ms-linkify "ms-namereference" reference (str "@" reference)))

            (and (= seg-type :nostrnamereference)
                 (or (.startsWith seg ":nostr:nevent1")
                     (.startsWith seg ":nostr:nprofile1")))
            seg

            (= seg-type :nostrnamereference)
            (str formatted-content (ms-linkify "ms-namereference" reference (str "nostr:" reference)))

            (= seg-type :nostrnpubreference)
            (let [author (get-author-name reference)]
              (str formatted-content (ms-linkify "ms-namereference" author (str "nostr:" author))))

            (= seg-type :idreference)
            (let [id (subs seg 1)]
              (str formatted-content (ms-linkify "ms-idreference" id (str "@" id))))

            (= seg-type :nostrnotereference)
            (str formatted-content (ms-linkify "ms-notereference" reference (str "nostr:" reference)))

            (= seg-type :nostreventreference)
            (str formatted-content (ms-linkify "ms-neventreference" reference "nostr:[event]"))

            (= seg-type :img)
            (str formatted-content (img-ify seg))

            :else
            formatted-content
            )))
      ""
      segments)))

(defn hexify-event [event]
  (assoc event :pubkey (hexify (:pubkey event))
               :id (hexify (:id event))
               :sig (->> (:sig event) (util/num->bytes 64) util/bytes->hex-string)))
