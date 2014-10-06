; TODO Review for compliance with https://github.com/bbatsov/clojure-style-guide

(ns ^{ :author "circlespainter" :internal true } leiningen.capsule.build
  "Creates the specified capsules for the project"

  (:require
    [clojure.string :as cstr]

    [cemerick.pomegranate.aether :as aether]

    [leiningen.capsule.aether :as aether-ported]
    [leiningen.capsule.utils :as cutils]
    [leiningen.capsule.consts :as cc])

  (:import
    (co.paralleluniverse.capsule.build Dependencies)
    (org.eclipse.aether.graph Dependency)
    (co.paralleluniverse.capsule Jar)
    (java.io File FileOutputStream)))

(defn- make-aether-dep [lein-style-spec]
  (Dependencies/toCapsuleDependencyString (@#'aether-ported/dependency lein-style-spec)))

(defn- jvm-lein-deps [project] (:dependencies project))

(defn- capsule-deps [project path & [profile-keyword]]
  (get-in project (cc/profile-aware-path project path profile-keyword)))

(defn- jvm-capsule-removed-deps [project & [profile-keyword]]
  (capsule-deps project cc/path-maven-dependencies-artifacts-jvm-remove profile-keyword))

(defn- jvm-capsule-added-deps [project & [profile-keyword]]
  (capsule-deps project cc/path-maven-dependencies-artifacts-jvm-add profile-keyword))

(defn- native-mac-capsule-deps [project & [profile-keyword]]
  (capsule-deps project cc/path-maven-dependencies-artifacts-native-mac profile-keyword))

(defn- native-linux-capsule-deps [project & [profile-keyword]]
  (capsule-deps project cc/path-maven-dependencies-artifacts-native-linux profile-keyword))

(defn- native-windows-capsule-deps [project & [profile-keyword]]
  (capsule-deps project cc/path-maven-dependencies-artifacts-native-windows profile-keyword))

(defn- jvm-computed-capsule-deps [project & [profile-keyword]]
  (conj
    (jvm-capsule-added-deps project profile-keyword)
    (seq
      (clojure.set/difference
        (set (jvm-lein-deps project profile-keyword))
        (set (jvm-capsule-removed-deps project profile-keyword))))))

(defn- xdual [mode]
  (case mode
    :except :xonly
    :only :xexcept
    :else mode))

(defn- dep-matches [dep1 dep2]
  (or
    (= dep1 dep2)
    (and
      (coll? dep1) (coll? dep2)
      (<= 1 (count dep1) (count dep2))
      (= (first dep1) (first dep2)))))

(defn- dep-matches-any [dep coll]
  (some #(dep-matches dep %) coll))

(defn- filter-deps [deps exceptions exceptions-mode]
  (if exceptions
    (filter
      #(let [match (dep-matches-any % exceptions)]
        (case (or exceptions-mode :except)
          (:except :xexcept) (not match)
          (:only :xonly) match
          :else true)))
    deps)
  deps)

(declare retrieve-and-insert-mvn-deps)

(defn- manifest-put-mvn-deps [project capsule & [profile-keyword exceptions exceptions-mode]]
  (let [jvm
        (cstr/join "," (map make-aether-dep
                            (filter-deps (jvm-computed-capsule-deps project profile-keyword) exceptions exceptions-mode)))
        native-mac
        (cstr/join "," (map make-aether-dep
                            (filter-deps (native-mac-capsule-deps project profile-keyword) exceptions exceptions-mode)))
        native-linux
        (cstr/join "," (map make-aether-dep
                            (filter-deps (native-linux-capsule-deps project profile-keyword) exceptions exceptions-mode)))
        native-win
        (cstr/join "," (map make-aether-dep
                            (filter-deps (native-windows-capsule-deps project profile-keyword) exceptions exceptions-mode)))
        insert-inline-deps
        (if (contains? #{:xexcept :xonly} exceptions-mode) (fn [_ _ & [_ _ _]]) retrieve-and-insert-mvn-deps)]
    (-> project
        (cutils/add-to-manifest "Dependencies" jvm profile-keyword)
        (cutils/add-to-manifest "Native-Dependencies-mac" native-mac profile-keyword)
        (cutils/add-to-manifest "Native-Dependencies-Linux" native-linux profile-keyword)
        (cutils/add-to-manifest "Native-Dependencies-Win" native-win profile-keyword)
        (insert-inline-deps project capsule profile-keyword exceptions (xdual exceptions-mode)))))

(defn- retrieve-and-insert-mvn-deps [project capsule & [profile-keyword exceptions exceptions-mode]]
  (let [jvm (filter-deps (jvm-computed-capsule-deps project profile-keyword) exceptions exceptions-mode)
        native-mac (filter-deps (native-mac-capsule-deps project profile-keyword) exceptions exceptions-mode)
        native-linux (filter-deps (native-linux-capsule-deps project profile-keyword) exceptions exceptions-mode)
        native-win (filter-deps (native-windows-capsule-deps project profile-keyword) exceptions exceptions-mode)
        insert-manifest-deps
        (if (contains? #{:xexcept :xonly} exceptions-mode) (fn [_ _ & [_ _ _]]) manifest-put-mvn-deps)]
    (doseq [dep (aether/resolve-dependencies (conj jvm native-mac native-linux native-win))]
      (.addEntry "/" capsule (:file (meta dep))))
    (insert-manifest-deps project capsule profile-keyword exceptions (xdual exceptions-mode))))

(defn- write-manifest [capsule project]
  (doseq [[k v] (get-in project cc/capsule-manifest-path)]
    (cond
      (string? v) (.setAttribute capsule k v)
      (coll? v)
      (doseq [[section-k section-v] v]
        (.setAttribute capsule k section-k section-v)))))

(defn- capsule-output-stream [project spec]
  (FileOutputStream. (str (:root project) "/build/" (:name spec))))

(defn- build-mixed-thin [project spec & [excepts]]
  ; Will add every dependency to the manifest
  (let [
         capsule (Jar.)
         project
         (reduce-kv
           (fn [project k _]
             (-> project
                 (retrieve-and-insert-mvn-deps capsule k excepts :only)
                 (manifest-put-mvn-deps capsule k excepts :except)))
           project
           (get-in project cc/path-profiles))]
    (write-manifest capsule project)
    (doto capsule
      (.setOutputStream (capsule-output-stream project spec))
      (.addClass (Class/forName "Capsule"))
      (.addPackageOf (Class/forName "capsule.UserSettings"))
      .close)))

(defn- build-mixed-fat [project spec & [excepts]]
  (let [capsule (Jar.)]
    (reduce-kv
      (fn [project k _]
        (-> project
            (retrieve-and-insert-mvn-deps capsule k excepts :except))
        (manifest-put-mvn-deps capsule k excepts :only))
      project
      (get-in project cc/path-profiles))
    (write-manifest capsule project)
    (doto capsule
      (.setOutputStream (capsule-output-stream project spec))
      (.addClass (Class/forName "Capsule"))
      .close)))

(defn- build-thin [project spec]
  "Builds a thin capsule"
  (build-mixed-thin project spec))

(defn- build-fat [project spec]
  "Builds a fat capsule"
  (build-mixed-fat project spec))

(defn- build-thin-except-clojure [project spec]
  "Builds a thin capsule embedding clojure"
  (build-mixed-thin project spec ['org.clojure/clojure]))

(defn- build-fat-except-clojure [project spec]
  "Builds a fat capsule excluding clojure"
  (build-mixed-fat project spec ['org.clojure/clojure]))

(defn- build-mixed [project spec]
  "Builds a mixed capsule"
  (cond
    (:fat-except spec) build-mixed-fat project spec (:fat-except spec)
    (:thin-except spec) build-mixed-fat project spec (:fat-except spec)
    :else
      (throw (RuntimeException. "Unexpected mixed capsule type specified, use either :fat-except or :thin-except"))))

(defn ^:internal build-capsule [project capsule-type-name capsule-type-spec]
  "Builds the capsule(s) of a given type using the pre-processed project map"
  (case capsule-type-name
    :thin
    (build-thin project capsule-type-spec)
    :fat
    (build-fat project capsule-type-spec)
    :thin-except-clojure
    (build-thin-except-clojure project capsule-type-spec)
    :fat-except-clojure
    (build-fat-except-clojure project capsule-type-spec)
    :mixed
    (build-mixed project capsule-type-spec)))