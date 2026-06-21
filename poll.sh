#!/usr/bin/env bash
set +e
for i in $(seq 1 50); do
  DONE=0
  TOTAL=0
  for J in apex-build-java apex-parallel-build apex-wait-scan apex-version apex-mixed; do
    STATE=$(curl -s "http://localhost:8088/job/$J/lastBuild/api/json" --user "admin:admin123" 2>/dev/null \
      | python -c "import json,sys; d=json.load(sys.stdin); print('build='+str(d.get('building',False))+'|result='+str(d.get('result')))" 2>/dev/null)
    printf "  %-22s %s\n" "$J" "$STATE"
    if [ -n "$STATE" ]; then
      TOTAL=$((TOTAL+1))
      if echo "$STATE" | grep -qE "result=(SUCCESS|FAILURE|UNSTABLE|ABORTED)"; then
        DONE=$((DONE+1))
      fi
    fi
  done
  echo "--- [$i/50] done=$DONE/$TOTAL ---"
  if [ "$DONE" -ge "$TOTAL" ] && [ "$TOTAL" -ge "5" ]; then
    echo "all done"
    break
  fi
  sleep 15
done
