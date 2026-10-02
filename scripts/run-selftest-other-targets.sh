#!/bin/bash
# 用 neoforge-1.21.1 的自测方式（runServer/runClient + -PrwSelfTest）逐个验证其他 target。
# 每个 target：先 runServer（生成世界 + 跑专用服务器自测），把世界搬到 saves/ 并放进夹具，
# 再 runClient -PrwQuickPlay=rewind_test -PrwSelfTest=true，最后把结论写进汇总文件。
ROOT=/d/work/Rewind
OUT=/tmp/rewind_test_summary.txt
: > "$OUT"

BASE_LOG=$ROOT/targets/neoforge-1.21.1/run/logs/latest.log

wait_marker() { # $1=file  $2=pattern  $3=最大等待秒数
  local f="$1" pat="$2" max="$3" i=0
  while [ "$i" -lt "$max" ]; do
    if [ -f "$f" ] && grep -qa "$pat" "$f" 2>/dev/null; then return 0; fi
    sleep 5; i=$((i + 5))
  done
  return 1
}

echo "### baseline neoforge-1.21.1" >> "$OUT"
wait_marker "$BASE_LOG" "REWIND_SELFTEST" 900
grep -a "REWIND_SELFTEST" "$BASE_LOG" | tail -1 >> "$OUT"
sleep 15

run_target() {
  local t="$1"
  local D="$ROOT/targets/$t"
  echo "" >> "$OUT"
  echo "===== $t" >> "$OUT"

  rm -f "$D/run/logs/latest.log"
  ( cd "$D" && ../../gradlew.bat runServer -PrwSelfTest=true > "/tmp/rewind_srv_$t.out" 2>&1 )
  wait_marker "$D/run/logs/latest.log" "REWIND_SERVERTEST" 900
  echo "  [server] $(grep -a 'REWIND_SERVERTEST' "$D/run/logs/latest.log" | tail -1)" >> "$OUT"
  echo "  [server-dir] $(ls "$D/run" | tr '\n' ' ')" >> "$OUT"

  # 把生成好的世界放进 saves/（客户端单人世界目录），并把夹具塞进 datapacks/
  if [ -d "$D/run/rewind_test" ]; then
    rm -rf "$D/run/saves/rewind_test"
    mkdir -p "$D/run/saves"
    cp -r "$D/run/rewind_test" "$D/run/saves/rewind_test"
    rm -f "$D/run/saves/rewind_test/session.lock"
    # 服务端自测刚在这个世界里建过存档点（quick/s1）：清掉索引，客户端自测要从空白时间线起跑，
    # 否则「单一根 / 第一个点就是根」这类断言会被上一次的残留状态带偏。
    rm -rf "$D/run/saves/rewind_test/rewind_snapshots"
    mkdir -p "$D/run/saves/rewind_test/datapacks"
    rm -rf "$D/run/saves/rewind_test/datapacks/rewind_test"
    cp -r "$D/testdata/rewind_test" "$D/run/saves/rewind_test/datapacks/rewind_test"
    echo "  [world] ok" >> "$OUT"
  else
    echo "  [world] MISSING run/rewind_test" >> "$OUT"
  fi

  rm -f "$D/run/logs/latest.log"
  ( cd "$D" && ../../gradlew.bat runClient -PrwQuickPlay=rewind_test -PrwSelfTest=true > "/tmp/rewind_cli_$t.out" 2>&1 )
  wait_marker "$D/run/logs/latest.log" "REWIND_SELFTEST" 1500
  echo "  [client] $(grep -a 'REWIND_SELFTEST' "$D/run/logs/latest.log" | tail -1)" >> "$OUT"
  grep -a 'REWIND_SELFTEST FAIL' "$D/run/logs/latest.log" | tail -1 >> "$OUT"
}

run_target fabric-1.20.1
run_target forge-1.20.1
run_target neoforge-26.1

echo "" >> "$OUT"
echo "### DONE" >> "$OUT"
