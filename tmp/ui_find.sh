uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
tr '>' '\n' < /sdcard/ui.xml | grep -E 'text="Connect Gmail' | grep -oE 'bounds="[^"]+"'
