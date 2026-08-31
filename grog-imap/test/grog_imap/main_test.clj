(ns grog-imap.main-test
  "MCP server (grog-imap.main) tests: tool discovery, thin-adapter behaviour,
  credential-provider injection, strict account selection, read-only mutation
  gating, and no-secret echoes. Tools run against the shared in-process fake
  IMAP server."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [grog-imap.core :as core]
            [grog-imap.main :as main]
            [grog-imap.support :as support]))

(def expected-tool-names
  ["imap_list_accounts" "imap_use_account" "imap_authenticate"
   "imap_list_mailboxes" "imap_search" "imap_fetch"
   "imap_set_flags" "imap_delete" "imap_move" "imap_copy" "imap_append"])

(defn- reset-mcp! [config]
  (reset! @#'grog-imap.main/config* config)
  (reset! @#'grog-imap.main/current* nil)
  (core/disconnect-all!))

(defn- tool-by-name [tools name]
  (or (first (filter #(= name (:name %)) tools))
      (throw (ex-info "tool not found" {:name name}))))

(defn- invoke [tools name args]
  (json/read-str ((:fn (tool-by-name tools name)) args) :key-fn keyword))

(defn- fake-config [port & [read-only]]
  {:accounts [{:name "dev" :host "127.0.0.1" :port port :user "u"
               :read-only read-only}]})

(deftest discovery
  (let [config (fake-config 1)
        _ (reset-mcp! config)
        names (set (map :name (main/build-tools)))]
    (is (= (set expected-tool-names) names))))

(deftest list-accounts-no-secrets
  (let [config (fake-config 1)
        _ (reset-mcp! config)
        tools (main/build-tools)
        result (invoke tools "imap_list_accounts" {})]
    (is (= ["dev"] (:accounts result)))
    (is (= #{:accounts} (set (keys result))) "only names; never credentials")))

(deftest use-account-allowlist
  (let [config (fake-config 1)
        _ (reset-mcp! config)
        tools (main/build-tools)]
    (testing "unknown account rejected before any I/O"
      (is (thrown? clojure.lang.ExceptionInfo
                   (invoke tools "imap_use_account" {:name "bogus"})))))
  (let [{:keys [port]} (support/start-fake-server)
        _ (reset-mcp! (fake-config port))
        tools (main/build-tools)]
    (testing "valid account selects and authenticates lazily"
      (with-redefs [main/credential-provider (fn [_] {:password "pw"})]
        (let [result (invoke tools "imap_use_account" {:name "dev"})]
          (is (= "dev" (:account result)))
          (is (true? (:active result)))
          (is (= #{:account :active} (set (keys result))) "no secret echoed"))))
    (core/disconnect-all!)))

(deftest authenticate-and-read
  (let [{:keys [port]} (support/start-fake-server)
        _ (reset-mcp! (fake-config port))
        tools (main/build-tools)]
    (with-redefs [main/credential-provider (fn [_] {:password "pw"})]
      (let [auth (invoke tools "imap_authenticate" {})]
        (is (= "dev" (:account auth)))
        (is (true? (:authenticated auth))))
      (testing "list mailboxes"
        (let [r (invoke tools "imap_list_mailboxes" {})]
          (is (= ["INBOX" "Trash"] (mapv :name (:mailboxes r))))))
      (testing "search"
        (let [r (invoke tools "imap_search" {:query "ALL"})]
          (is (= [1 2 3] (:ids r)))))
      (testing "fetch"
        (let [r (invoke tools "imap_fetch" {:seq 1 :items ["uid" "flags"]})]
          (is (= 1 (count (:messages r))))
          (let [m (first (:messages r))]
            (is (= 42 (:uid m)))
            ;; flags come back as strings through JSON encoding
            (is (= ["seen"] (:flags m)))))))
    (core/disconnect-all!)))

(defn- select-dev! [tools]
  (with-redefs [main/credential-provider (fn [_] {:password "pw"})]
    (invoke tools "imap_use_account" {:name "dev"})))

(deftest read-only-default-gating
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)]
    (reset-mcp! config)
    (let [tools (main/build-tools)]
      (select-dev! tools)
      (is (thrown? clojure.lang.ExceptionInfo
                   (invoke tools "imap_set_flags" {:seq 1 :flags ["Seen"]})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (invoke tools "imap_delete" {:seq 1})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (invoke tools "imap_move" {:seq 1 :destination "Trash"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (invoke tools "imap_copy" {:seq 1 :destination "Trash"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (invoke tools "imap_append" {:mailbox "INBOX" :content "x"}))))
    (core/disconnect-all!)))

(deftest read-only-false-allows-mutation
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port false)]
    (reset-mcp! config)
    (let [tools (main/build-tools)]
      (select-dev! tools)
      (is (= {:ok true} (invoke tools "imap_set_flags" {:seq 1 :flags ["Seen"]})))
      (is (= {:deleted true} (invoke tools "imap_delete" {:seq 1})))
      (is (= {:moved true} (invoke tools "imap_move" {:seq 1 :destination "Trash"})))
      (is (= {:copied true} (invoke tools "imap_copy" {:seq 1 :destination "Trash"})))
      (is (= {:appended true} (invoke tools "imap_append"
                                      {:mailbox "INBOX" :content "Subject: x\r\n\r\nb"}))))
    (core/disconnect-all!)))

(deftest missing-credential-errors
  (let [{:keys [port]} (support/start-fake-server)
        config (fake-config port)]
    (reset-mcp! config)
    (let [tools (main/build-tools)]
      ;; provider throws like the real env! when GROG_IMAP_PASSWORD_* is unset
      (with-redefs [main/credential-provider
                    (fn [_] (throw (ex-info "Missing env GROG_IMAP_PASSWORD_DEV" {})))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (invoke tools "imap_authenticate" {})))))
    (core/disconnect-all!)))
