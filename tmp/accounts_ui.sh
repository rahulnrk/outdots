cmd statusbar collapse >/dev/null 2>&1
am start -n app.mini.hub/.android.MainActivity >/dev/null 2>&1
sleep 2
focus=$(dumpsys window | grep mCurrentFocus)
case "$focus" in *app.mini.hub*) ;; *) echo "MINI not focused; refusing to dump"; exit 0;; esac
uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
b=$(tr '>' '\n' < /sdcard/ui.xml | grep 'text="Accounts"' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
set -- $(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
[ -n "$4" ] && input tap $((($1+$3)/2)) $((($2+$4)/2))
sleep 2
uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
# Only account-dialog labels; addresses and names masked.
grep -oE 'text="[^"]+"' /sdcard/ui.xml \
  | grep -E ' · (Connected|Disconnected|Access removed|Sign-in expired|Needs permission|Temporarily unavailable|Syncing)"|Disconnect|Reconnect|Local history|Google access|Sync Gmail now|Sync finished|Connect Gmail|No authenticated|need attention' \
  | sed -E 's/text="[^"]* · /text="<account> · /; s/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+/<address>/g'
rm -f /sdcard/ui.xml
