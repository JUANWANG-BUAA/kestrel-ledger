#!/usr/bin/env bash
# 安装数据采集定时任务（交易日盘前/盘后 + 周任务 + 月度维护）
# 用法：bash tools/install_cron.sh && bash tools/install_cron.sh --uninstall
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="$(command -v python3)"
MARK="# >>> market-data-pipeline >>>"
END="# <<< market-data-pipeline <<<"
CRON=$(cat <<CRONEOF
$MARK
SHELL=/bin/bash
PATH=/usr/local/bin:/usr/bin:/bin:/opt/homebrew/bin
0 9  * * 1-5 cd $REPO && $PY tools/daily.py --pre  >> $REPO/logs/daily.log 2>&1
35 15 * * 1-5 cd $REPO && $PY tools/daily.py --post >> $REPO/logs/daily.log 2>&1
30 3  * * 6  cd $REPO && $PY tools/daily.py --weekly >> $REPO/logs/daily.log 2>&1
0 4   1 * *  cd $REPO && $PY tools/archive.py --vacuum >> $REPO/logs/daily.log 2>&1
$END
CRONEOF
)

if [[ "${1:-}" == "--uninstall" ]]; then
  crontab -l 2>/dev/null | sed "/$MARK/,/$END/d" | crontab - || true
  echo "✅ 已卸载"; crontab -l 2>/dev/null | tail -5; exit 0
fi

current=$(crontab -l 2>/dev/null || true)
echo "$current" | sed "/$MARK/,/$END/d" > /tmp/cron.new || true
{ cat /tmp/cron.new; echo "$CRON"; } | crontab -
echo "✅ 已安装定时任务（仓库：$REPO，解释器：$PY）"
echo
crontab -l | sed -n "/$MARK/,/$END/p"
