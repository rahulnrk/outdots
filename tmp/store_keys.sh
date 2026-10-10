US=$(printf '\037')
run-as app.mini.hub cat shared_prefs/mini_connected_accounts.xml | grep -oE '<string name="account_[^"]+">[^<]+' | while read line; do
  v=$(echo "$line" | sed -E 's/.*">//')
  lid=$(echo "$v" | cut -d"$US" -f2 | base64 -d 2>/dev/null)
  st=$(echo "$v" | cut -d"$US" -f7 | base64 -d 2>/dev/null)
  ls=$(echo "$v" | cut -d"$US" -f9 | base64 -d 2>/dev/null)
  act=$(echo "$v" | cut -d"$US" -f10 | base64 -d 2>/dev/null | cut -c1-40)
  echo "account=$lid state=$st lastSync=$ls action=$act"
done
