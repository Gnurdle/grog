(ns grog-office.smoke
  "Manual smoke test for grog-office.core — not part of the MCP server."
  (:require [grog-office.core :as core])
  (:import [org.apache.poi.xwpf.usermodel XWPFDocument XWPFParagraph XWPFRun XWPFTable]
           [java.io FileOutputStream]))

(defn -main [& _]
  (let [dir (System/getProperty "java.io.tmpdir")
        src (str dir "/grog-office-smoke-in.docx")
        out (str dir "/grog-office-smoke-out.docx")]
    ;; 1) build a sample docx
    (with-open [doc (XWPFDocument.)]
      (let [p (.createParagraph doc)
            r1 (.createRun p)
            _ (.setText r1 "Item ")
            r2 (.createRun p)
            _ (.setBold r2 true)
            _ (.setText r2 "(J-05) 4.3” Touch Display Panel")
            table (.createTable doc 2 2)]
        (.setText (.getCell (.getRow table 0) 0) "Part")
        (.setText (.getCell (.getRow table 0) 1) "Desc")
        (.setText (.getCell (.getRow table 1) 0) "E-05")
        (.setText (.getCell (.getRow table 1) 1) "Ghost row target")
        (with-open [out-s (FileOutputStream. src)]
          (.write doc out-s))))

    ;; 2) import
    (let [h (core/import-document! src)]
      (println "import ->" (pr-str h))
      ;; 3) list blocks
      (let [{:keys [count blocks]} (core/list-blocks h true)]
        (println "blocks:" count)
        (doseq [b blocks] (println "  " (pr-str b))))
      ;; 4) find
      (println "find (J-05) ->" (pr-str (core/find-text h "(J-05)" 10)))
      ;; 5) replace scoped, preserving first-run rPr
      (println "replace ->" (pr-str (core/replace-text h "4.3”" "3.5”" {:block-id "para.1"})))
      (println "after replace get para.1 ->" (pr-str (core/get-text h "para.1" nil)))
      ;; 6) delete table row
      (println "delete row ->" (pr-str (core/delete-table-row h "table.1" 1)))
      (println "table.1 text ->" (pr-str (core/get-text h "table.1" nil)))
      ;; 7) save + re-import to confirm persisted
      (core/save! h out)
      (core/close-handle! h)
      (let [h2 (core/import-document! out)]
        (println "re-import para.1 ->" (pr-str (core/get-text h2 "para.1" nil)))
        (println "re-import table.1 rows ->" (pr-str (:rows (core/get-text h2 "table.1" nil))))
        (core/close-handle! h2))
      (println "SMOKE OK"))))
