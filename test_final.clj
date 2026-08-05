(ns test-final
  (:require [grog.md-render :as md]
            [grog.md-stream :as ms]))

(def source
  (str ". Install rclone and FUSE\n\n"
       "Most distros package both. The FUSE userspace libraries are required for  rclone mount .\n\n\n\n"
       "# Debian / Ubuntu\n\n"
       "sudo apt update\n\n"
       "sudo apt install rclone fuse3\n\n\n"
       "# Fedora / RHEL / AlmaLinux\n\n"
       "sudo dnf install rclone fuse3\n\n\n"
       "# Arch\n\n"
       "sudo pacman -S rclone fuse3\n\n\n\n"
       "> Use fuse3 when possible. If you only have  fuse  (fuse2), rclone will usually still work, but fuse3 is preferred.\n\n\n"
       "2. Configure a remote\n\n"
       "Run the interactive configurator:\n\n"
       "```\n"
       "rclone config\n"
       "```\n\n"
       "Follow the prompts...\n\n"
       "## Common useful options\n\n"
       "| Option | Why use it |\n"
       "|---|---|\n"
       "|  --daemon  | Run in background |\n\n"
       "A more production-ready command:\n\n"
       "```\n"
       "rclone mount mygdrive: /mnt/mygdrive \\\n"
       "  --daemon \\\n"
       "  --allow-other\n"
       "```\n\n"
       "8. Important caveats\n\n"
       "-  rclone mount  is not a POSIX filesystem. Apps that depend heavily on random writes, file locking, or mmap may not work perfectly.\n"))

(defn simulate-stream [chunks]
  (let [s (atom (ms/empty-state))]
    (print "\n\nchat> ")
    (doseq [chunk chunks]
      (let [[emitted new-state] (ms/feed @s chunk)]
        (reset! s new-state)
        (doseq [e emitted]
          (when (seq e)
            (print (md/render-to-ansi e))))))
    (let [[emitted _] (ms/finish @s)]
      (doseq [e emitted]
        (when (seq e)
          (print (md/render-to-ansi e))))))
  (print "\u001B[0m")
  (println))

(println "=== Streaming (one chunk) ===")
(simulate-stream [source])
(println)
(println "=== Full render ===")
(print (md/render-to-ansi source))
(println "\u001B[0m")
