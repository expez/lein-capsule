; Partially copied from https://github.com/ryfow/pomegranate (relevant rights and licenses apply)

; TODO Remove when https://github.com/cemerick/pomegranate/pull/59 is merged

(ns leiningen.capsule.aether
  (:refer-clojure :exclude  [type proxy])
  (:import (org.eclipse.aether.repository Authentication RepositoryPolicy LocalRepository RemoteRepository RemoteRepository$Builder
                                          Proxy)
           (org.eclipse.aether.graph Dependency Exclusion)
           (org.eclipse.aether.artifact DefaultArtifact)
           (capsule.org.eclipse.aether.util.repository AuthenticationBuilder)
           (org.sonatype.aether.util.repository DefaultProxySelector)))

(def update-policies {:daily RepositoryPolicy/UPDATE_POLICY_DAILY
                      :always RepositoryPolicy/UPDATE_POLICY_ALWAYS
                      :never RepositoryPolicy/UPDATE_POLICY_NEVER})

(def checksum-policies {:fail RepositoryPolicy/CHECKSUM_POLICY_FAIL
                        :ignore RepositoryPolicy/CHECKSUM_POLICY_IGNORE
                        :warn RepositoryPolicy/CHECKSUM_POLICY_WARN})

(defn- policy
  [policy-settings enabled?]
  (RepositoryPolicy.
    (boolean enabled?)
    (update-policies (:update policy-settings :daily))
    (checksum-policies (:checksum policy-settings :fail))))

(defn- set-policies
  [repo settings]
  (doto repo
    (.setSnapshotPolicy (policy settings (:snapshots settings true)))
    (.setReleasePolicy (policy settings (:releases settings true)))))

(defn- set-authentication
  "Calls the setAuthentication method on obj"
  [obj {:keys [username password passphrase private-key-file] :as settings}]
  (if (or username password private-key-file passphrase)
    (doto obj
      (.setAuthentication
       (.build
        (doto (AuthenticationBuilder.)
          (.addUsername username)
          (.addPassword password)
          (.addPrivateKey private-key-file passphrase)))))
    obj))

(defn- set-proxy 
  [repo-builder {:keys [type host port non-proxy-hosts ]
                 :or {type "http"}
                 :as proxy} ]
  (if (and repo-builder host port)
    (let [prx-sel (doto (DefaultProxySelector.)
                    (.add (set-authentication (Proxy. type host port nil) proxy)
                          non-proxy-hosts))
          prx (.getProxy prx-sel (.build repo-builder))] ; ugg.
      ;; Don't know how to get around "building" the repo for this
      (.setProxy repo-builder prx))
    repo-builder))

(defn- make-repository
  [[id settings] proxy]
  (let [settings-map (if (string? settings)
                       {:url settings}
                       settings)] 
    (.build
     (doto (RemoteRepository$Builder. id
                                      (:type settings-map "default")
                                      (str (:url settings-map)))
       (set-policies settings-map)
       (set-authentication settings-map)
       (set-proxy proxy)))))

(defn- group
  [group-artifact]
  (or (namespace group-artifact) (name group-artifact)))


(defn- coordinate-string
  "Produces a coordinate string with a format of
   <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>>
   given a lein-style dependency spec.  :extension defaults to jar."
  [[group-artifact version & {:keys [classifier extension] :or {extension "jar"}}]]
  (->> [(group group-artifact) (name group-artifact) extension classifier version]
    (remove nil?)
    (interpose \:)
    (apply str)))

(defn- exclusion
  [[group-artifact & {:as opts}]]
  (Exclusion.
    (group group-artifact)
    (name group-artifact)
    (:classifier opts "*")
    (:extension opts "*")))

(defn- normalize-exclusion-spec [spec]
  (if (symbol? spec)
    [spec]
    spec))

(defn- dependency
  [[group-artifact version & {:keys [scope optional exclusions]
                              :as opts
                              :or {scope "compile"
                                   optional false}}
    :as dep-spec]]
  (Dependency. (DefaultArtifact. (coordinate-string dep-spec))
               scope
               optional
               (map (comp exclusion normalize-exclusion-spec) exclusions)))