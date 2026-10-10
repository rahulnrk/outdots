i=0
while [ $i -lt 300 ]; do
  out=$(logcat -d -s MiniGmailOAuth:*)
  if echo "$out" | grep -qE 'stored account|failed|rejected|provider error|accepted=false'; then
    echo "$out" | grep MiniGmailOAuth
    exit 0
  fi
  sleep 2
  i=$((i+1))
done
echo "TIMEOUT: no OAuth callback outcome logged in 10 minutes"
logcat -d -s MiniGmailOAuth:*
