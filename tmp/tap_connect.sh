uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
b=$(tr '>' '\n' < /sdcard/ui.xml | grep 'text="Connect Gmail' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
echo "button: $b"
nums=$(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
set -- $nums
if [ -n "$4" ]; then input tap $((($1+$3)/2)) $((($2+$4)/2)); echo tapped; fi
sleep 3
dumpsys window | grep mCurrentFocus
