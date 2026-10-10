echo "--- prefs files"
run-as app.mini.hub ls shared_prefs
echo "--- account id count"
run-as app.mini.hub cat shared_prefs/mini_connected_accounts.xml 2>/dev/null | grep -c '<string>'
echo "--- oauth activity starts"
logcat -d | grep 'ActivityTaskManager: START' | grep -E 'accounts.google.com|googleusercontent' | sed -E 's/.*(START u0 \{act=[^ ]+ ).*(dat=[a-z.]+[^ ]{0,30}).* from uid [0-9]+ \(([^)]+)\).*/\1\2 from \3/' | cut -c1-160
echo "--- mini errors"
logcat -d | grep -iE 'mini-gmail|NetworkOnMain|FATAL|app.mini.hub.*Exception' | cut -c1-200 | tail -20
echo "--- screen"
uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
grep -oE 'text="[^"]*(onnect|ccount|uthoriz|Gmail|finish|Finish|Google)[^"]*"' /sdcard/ui.xml | grep -v '@' | head -20
