(ns frontend.keyboards.config
  (:require [frontend.util :refer [mac?]]))

(def default-shortcuts
  {:date-picker/complete "alt+a"
   :date-picker/prev-day "alt+h"
   :date-picker/next-day "alt+l"
   :date-picker/prev-week "alt+k"
   :date-picker/next-week "alt+j"

   :auto-complete/prev "alt+p"
   :auto-complete/next "alt+n"
   :auto-complete/complete "alt+a"

   :editor/clear-selection "esc"
   :editor/toggle-document-mode "t d"
   :editor/toggle-settings (if mac? "t s" ["t s" "mod+,"])
   :editor/undo "mod+z"
   :editor/redo ["shift+mod+z" "mod+y"]
   :editor/zoom-in (if mac? "mod+." "alt+right")
   :editor/zoom-out (if mac? "mod+," "alt+left")
   :editor/cycle-todo "mod+enter"
   :editor/expand-block-children "mod+down"
   :editor/collapse-block-children "mod+up"
   :editor/follow-link "mod+o"
   :editor/open-link-in-sidebar "mod+shift+o"
   :editor/bold "mod+b"
   :editor/italics "mod+i"
   :editor/highlight "mod+h"
   :editor/insert-link "mod+k"
   :editor/select-all-blocks "mod+shift+a"
   :editor/move-block-up (if mac? "mod+shift+up"  "alt+shift+up")
   :editor/move-block-down (if mac? "mod+shift+down" "alt+shift+down")
   :editor/save "mod+s"
   :editor/next "down"
   :editor/prev "up"

   ;; '?' not in goog.events.KeyNames
   ;; actually keycode is deprecated, use e.code or e.key is recommended?
   ;; did not find ways to use that with closure library
   :ui/toggle-help "shift+/"
   :ui/toggle-theme "t t"
   :ui/toggle-right-sidebar "t r"
   :ui/toggle-new-block "t e"
   :ui/show-contents "t c"
   :ui/toggle-wide-mode "t w"
   :ui/toggle-between-page-and-file "s"
   :ui/fold "tab"
   :ui/un-fold "shift+tab"
   :ui/toggle-brackets "mod+c mod+b"

   :go/search "mod+u"
   :go/journals (if mac? "mod+j" "alt+j")

   :git/commit "g c"

   :search/re-index "mod+c mod+s"
   :graph/re-index "mod+c mod+r"
   })
