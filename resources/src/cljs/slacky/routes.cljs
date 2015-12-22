(ns slacky.routes
  (:require [goog.events :as events]
            [reagent.dom :as r]
            [secretary.core :as secretary :refer-macros [defroute]]
            [slacky.views.demo :as demo])
  (:import [goog.history Html5History EventType]))

(def app (js/document.getElementById "app"))

(defn- home-component []
  [:div.row
   [:div.col-xs-10.col-xs-offset-1
    [demo/component]]

   [:div.col-xs-10.col-xs-offset-1
    [:p "Hello world"]]])

(defroute "/demo" {:as params}
  (r/render [demo/component] app))

(defroute "/" {:as params}
  (r/render [demo/component] app))

(let [h (Html5History.)]
  (goog.events/listen h EventType.NAVIGATE #(secretary/dispatch! (.-token %)))
  (doto h (.setEnabled true)))
