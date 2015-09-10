(ns slacky.integration-test
  (:require [clj-http.client :as http]
            [clojure.core.async :as a]
            [clojure.test :refer :all]
            [conjure.core :as cj]
            [slacky
             [memecaptain :as memecaptain]
             [fixture :refer [with-web-api with-database with-fake-internet]]
             [service :as service]
             [google :as google]
             [slack :as slack]])
  (:import [java.util UUID]))

(use-fixtures :once with-web-api with-database)

(deftest can-generate-memes
  (with-fake-internet {}
    (is (= meme-url
           (:body (http/post "http://localhost:8080/api/meme"
                             {:throw-exceptions? false
                              :form-params {:text "cats | cute cats | FTW"}}))))

    (cj/verify-called-once-with-args memecaptain/create-template "http://images.com/cat.jpg")
    (cj/verify-called-once-with-args memecaptain/create-instance template-id "cute cats" "FTW")))


(deftest can-integrate-with-slack
  (let [token (clojure.string/replace (str (UUID/randomUUID)) "-" "")
        webhook-url "https://hooks.slack.com/services/foobarbaz"
        channel-name "my-channel"
        user-name "the-user"]

    (testing "can create an account"
      (is (= 200 (:status (http/post "http://localhost:8080/api/account"
                                     {:throw-exceptions? false
                                      :form-params {:token token
                                                    :key webhook-url}})))))

    (testing "can meme from a channel"
      (with-fake-internet {}
        (is (= "Your meme is on its way"
               (:body (http/post "http://localhost:8080/api/slack/meme"
                                 {:throw-exceptions? false
                                  :form-params {:token token
                                                :team_id "a"
                                                :team_domain "b"
                                                :channel_id "c"
                                                :channel_name channel-name
                                                :user_id "d"
                                                :user_name user-name
                                                :command "/meme"
                                                :text "cats | cute cats | FTW"}}))))

        (is (= [webhook-url (str "#" channel-name)
                (slack/->message :meme user-name "cats | cute cats | FTW" meme-url)]
               (first (a/alts!! [slack-channel (a/timeout 500)]))))))


    (testing "can register a template"
      (with-fake-internet {:template-id "cute-cat-template-id"}
        (is (= "Your template is being registered"
               (:body (http/post "http://localhost:8080/api/slack/meme"
                                 {:throw-exceptions? false
                                  :form-params {:token token
                                                :team_id "a"
                                                :team_domain "b"
                                                :channel_id "c"
                                                :channel_name channel-name
                                                :user_id "d"
                                                :user_name user-name
                                                :command "/meme"
                                                :text ":template cute cats http://cats.com/cute.jpg"}}))))

        (is (= [webhook-url (str "#" channel-name)
                (slack/->message :add-template user-name nil
                                 "cute cats" "http://cats.com/cute.jpg")]
               (first (a/alts!! [slack-channel (a/timeout 500)]))))

        (cj/verify-first-call-args-for memecaptain/create-template "http://cats.com/cute.jpg")

        (testing "and can use it in a meme"
          (is (= "Your meme is on its way"
                 (:body (http/post "http://localhost:8080/api/slack/meme"
                                   {:throw-exceptions? false
                                    :form-params {:token token
                                                  :team_id "a"
                                                  :team_domain "b"
                                                  :channel_id "c"
                                                  :channel_name channel-name
                                                  :user_id "d"
                                                  :user_name user-name
                                                  :command "/meme"
                                                  :text "cute cats | omg | so cute"}}))))

          (is (= [webhook-url (str "#" channel-name)
                  (slack/->message :meme user-name "cute cats | omg | so cute" meme-url)]
                 (first (a/alts!! [slack-channel (a/timeout 500)]))))

          (cj/verify-call-times-for memecaptain/create-template 1)
          (cj/verify-first-call-args-for memecaptain/create-instance template-id "omg" "so cute"))))))
