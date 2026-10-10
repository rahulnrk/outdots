uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
grep -oE 'text="[^"]+"' /sdcard/ui.xml | head -40
grep -oE 'content-desc="[^"]+"' /sdcard/ui.xml | head -20
dumpsys window | grep -E 'mCurrentFocus'
