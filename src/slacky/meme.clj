(ns slacky.meme
  (:require [clj-http
             [client :as http]
             [conn-mgr :refer [make-reusable-conn-manager]]]
            [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [slacky.slack :as slack]))

(def memecaptain-url "http://memecaptain.com/")

(def connection-pool (make-reusable-conn-manager {:timeout 10 :threads 4 :default-per-route 4}))

(defn- poll-for-result [polling-url]
  (log/debug "Polling" polling-url)
  (loop [attempts 0]
    (let [resp (http/get polling-url {:connection-manager connection-pool :follow-redirects false})
          status (:status resp)]

      (cond

        (= 303 status)
        (get-in resp [:headers "location"])

        (< 10 attempts)
        (throw (Exception. (str "Timed out waiting")))

        (= 200 status)
        (do (log/debug "Meme not ready, sleeping")
            (Thread/sleep 1000)
            (recur (inc attempts)))

        :else
        (throw (ex-info (str "Unexpected response from " polling-url)
                        resp))))))

(defn- google-image-search [term]
  (log/info "Googling for images matching" term)
  (let [resp (http/get "http://ajax.googleapis.com/ajax/services/search/images"
                       {:connection-manager connection-pool
                        :headers {:Content-Type "application/json"
                                  :Accept "application/json"}
                        :query-params {:q term
                                       :v 1.0
                                       :rsz 8
                                       :safe "active"}})]

    (when-let [body (and (= 200 (:status resp))
                         (:body resp))]
      (-> body (json/decode keyword) :responseData :results not-empty rand-nth :unescapedUrl))))


(defn- create-template [image-url]
  (when image-url
    (log/info "Creating template for image" image-url)
    (let [resp (http/post (str memecaptain-url "/src_images")
                          {:connection-manager connection-pool
                           :headers {:Content-Type "application/json"
                                     :Accept "application/json"}
                           :follow-redirects false
                           :body (json/encode {:url (.trim image-url)})})]

      (if-let [polling-url (and (= 202 (:status resp))
                                (not-empty (get-in resp [:headers "location"])))]

        (do (poll-for-result polling-url)
            (-> resp :body (json/decode keyword) :id))

        (throw (ex-info (str "Unexpected response from /src_images")
                        resp))))))


(defn- resolve-template-id [term]
  (log/debug "Resolving template for term" term)
  (cond
    (string/blank? term)
    nil

    (re-matches #"^https?://.*$" term)
    (create-template term)

    :else
    (create-template (google-image-search term)))

  ;; allow pre-baked ones?
  )

(defn- create-instance [template-id text-upper text-lower]
  (log/info "Generating meme based on template" template-id)
  (let [resp (http/post (str memecaptain-url "/gend_images")
                        {:connection-manager connection-pool
                         :headers {:Content-Type "application/json"
                                   :Accept "application/json"}
                         :follow-redirects false
                         :body (json/encode {:src_image_id template-id
                                             :captions_attributes
                                             [{:text text-upper
                                               :top_left_x_pct 0.05
                                               :top_left_y_pct 0
                                               :width_pct 0.9
                                               :height_pct 0.25}
                                              {:text text-lower
                                               :top_left_x_pct 0.05
                                               :top_left_y_pct 0.75
                                               :width_pct 0.9
                                               :height_pct 0.25}]})})]

    (if-let [polling-url (and (= 202 (:status resp))
                              (not-empty (get-in resp [:headers "location"])))]

      (poll-for-result polling-url)

      (throw (ex-info (str "Unexpected response")
                      resp)))))

(def command-pattern #"<?([^>]*)>?\s?\|\s?(.*)\s?\|\s?(.*)\s?")

(defn valid-command? [{:keys [text]}]
  (re-matches command-pattern text))

(defn generate-meme [{:keys [user_name text command]} respond-to]
  (let [[_ template-search text-upper text-lower]
        (map #(.trim %) (re-matches command-pattern text))]

    (if-let [template-id (resolve-template-id template-search)]
      (try (let [meme-url (create-instance template-id text-upper text-lower)]
             (log/info "Generated meme" meme-url)
             (respond-to :channel (slack/meme-message user_name text meme-url)))
           (catch Exception e
             (log/error "Blew up attempting to generate meme" e)
             (respond-to :user (str "You broke me. Check my logs for details!"
                                    "\n" "`" command " " text "`"))))

      (respond-to :user
                  (str "Couldn't find a good template for the meme, try specifying a url instead"
                       "\n" "`" command " " text "`")))))
