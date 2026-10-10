am start -n app.mini.hub/.android.MainActivity >/dev/null 2>&1
sleep 2
uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
b=$(tr '>' '\n' < /sdcard/ui.xml | grep 'text="Accounts"' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+,[0-9]+' | head -1)
x=${b%,*}; y=${b#*,}
input tap $((x+40)) $((y+20))
sleep 2
uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
b=$(tr '>' '\n' < /sdcard/ui.xml | grep 'text="Connect Gmail' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+,[0-9]+' | head -1)
echo "connect button at: $b"
grep -oE 'text="(No authenticated[^"]*|Gmail account connected[^"]*|Gmail authorization[^"]*|Google authorization[^"]*|Finishing[^"]*)"' /sdcard/ui.xml
if [ -n "$b" ]; then x=${b%,*}; y=${b#*,}; input tap $((x+40)) $((y+20)); echo tapped-connect; fi
