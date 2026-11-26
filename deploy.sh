#!/usr/bin/env bash
# ==============================================================================
# 私教工作室 · 单机部署脚本（在【服务器】上运行，不是开发机）
#
# 用法：
#   ./deploy.sh                        # 部署 ~/incoming/ 下的后端 jar + 前端 dist
#   ./deploy.sh --backend-only         # 只换 jar（前端没改时用）
#   ./deploy.sh --frontend-only        # 只换 dist（不重启后端）
#   ./deploy.sh --incoming /path       # 指定产物目录
#   ./deploy.sh --help
#   sudo tail -F /var/log/fitness/app.log  # 在另一个 SSH 窗口实时看应用启动日志
#
# 统一使用 ubuntu 账号，不再单独创建 app 用户。CI / 本地先把产物放到服务器的 ~/incoming/：
#   scp target/app.jar ubuntu@<IP>:~/incoming/app.jar
#   scp -r ../vue3-jianshenfang/dist/* ubuntu@<IP>:~/incoming/dist/
#   ssh ubuntu@<IP> "sudo /opt/fitness/deploy.sh"
#
# 非 root 执行时脚本会自动 sudo 提权重入（/opt/fitness、/var/www 均为 root 属主），
# 所以 ubuntu 直接 ./deploy.sh 也能跑，不需要手工 chown。CI 场景需 sudo 免密：
#   腾讯云 Ubuntu 镜像的 ubuntu 用户默认已是 NOPASSWD，若被改过用 sudo visudo 确认。
#
# 首次部署前置（一次性，全部在 ubuntu 下执行）：
#   sudo mkdir -p /opt/fitness /var/www/fitness /var/backups/fitness/deploy /var/log/fitness
#   sudo cp deploy.sh /opt/fitness/deploy.sh && sudo chmod 755 /opt/fitness/deploy.sh
#   # 应用以 ubuntu 身份运行，上传目录和日志要归它，否则运行时写不进去：
#   sudo chown -R ubuntu:ubuntu /opt/fitness/upload /var/log/fitness
#
# 设计约定：
#   - 构建在 CI / 开发机做，服务器只做「换包 + 重启」，避免 2C4G 上跑 Maven/npm 导致 OOM
#   - 自动备份上一版 jar 与 dist，健康检查失败自动回滚
#   - 不动 application-prod.yml（600）、schema.sql、upload/、数据库
#   - 生产 sql.init.mode=never，表结构变更必须单独评审执行，本脚本绝不自动建表
# ==============================================================================
set -Eeuo pipefail

# --help 不该为了看文档还要提权，先拦掉（放在任何环境探测之前）
case "${1:-}" in
  -h|--help) sed -n '2,/^set -Eeuo pipefail/p' "$0" | sed '$d'; exit 0 ;;
esac

APP_DIR=/opt/fitness
WEB_DIR=/var/www/fitness
BACKUP_DIR=/var/backups/fitness/deploy
SERVICE=fitness
APP_LOG=/var/log/fitness/app.log
JAR_NAME=app.jar
HEALTH_URL=http://127.0.0.1:8080/api/courses
KEEP=3                       # 保留最近几份备份，防止磁盘涨满

# 提权后 $HOME 会变成 /root，产物目录必须按「发起部署的账号」解析回它的 ~/incoming，
# 否则 sudo 之后会跑去 /root/incoming 找文件。
REAL_USER="${SUDO_USER:-$(id -un)}"
REAL_HOME="$(getent passwd "$REAL_USER" | cut -d: -f6 || true)"
INCOMING="${INCOMING_DIR:-${REAL_HOME:-/root}/incoming}"
DEPLOY_BACKEND=1
DEPLOY_FRONTEND=1

log()  { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
die()  { printf '[%s] ERROR: %s\n' "$(date '+%F %T')" "$*" >&2; exit 1; }

# /opt/fitness 与 /var/www 都是 root 属主，非 root 直接跑会 Permission denied；
# 这里自动提权重入，ubuntu 用户不必切换账号，也不用手工 chown 目标目录。
if [[ $EUID -ne 0 ]]; then
  command -v sudo >/dev/null 2>&1 || die "当前非 root 且没有 sudo，请改用 root 执行"
  SELF="$(readlink -f "$0")"
  log "以 $(id -un) 身份发起，自动提权执行"
  exec sudo INCOMING_DIR="${INCOMING_DIR:-}" bash "$SELF" "$@"
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend-only)  DEPLOY_FRONTEND=0; shift ;;
    --frontend-only) DEPLOY_BACKEND=0;  shift ;;
    --incoming)      [[ $# -ge 2 ]] || die "--incoming 缺少路径"; INCOMING="$2"; shift 2 ;;
    -h|--help)       sed -n '2,/^set -Eeuo pipefail/p' "$0" | sed '$d'; exit 0 ;;
    *) die "未知参数: $1（用 --help 查看用法）" ;;
  esac
done

(( DEPLOY_BACKEND || DEPLOY_FRONTEND )) || die "--backend-only 与 --frontend-only 不能同时使用"

# 固定路径保护：避免变量被改错时 rm -rf 误删别的目录
[[ "$APP_DIR" == /opt/fitness && "$WEB_DIR" == /var/www/fitness ]] \
  || die "部署路径不符合预期，已中止"
[[ -d "$APP_DIR" ]] || die "$APP_DIR 不存在，请先按部署文档初始化目录"
[[ -d "$WEB_DIR" ]] || die "$WEB_DIR 不存在，请先按部署文档初始化目录"

# ---------- 0. 串行化部署 ----------
# 前后端是两个独立 GitHub 仓库，可能同时 push；仓库级 concurrency 管不到跨仓库，
# 所以在服务器侧用文件锁排队，避免两次部署同时备份/替换/重启互相踩。
command -v flock >/dev/null 2>&1 || die "缺少 flock（util-linux），无法安全串行部署"
LOCK_FILE="$APP_DIR/.deploy.lock"
exec 9>"$LOCK_FILE" || die "无法创建部署锁 $LOCK_FILE"
flock -w 180 9 || die "等待部署锁超时（180s），可能有其他部署卡住，请检查后重试"

NEW_JAR="$INCOMING/app.jar"
NEW_DIST="$INCOMING/dist"
STAGE="${WEB_DIR}.new"
OLD_WEB="${WEB_DIR}.old"
SNAPSHOT=""

if (( DEPLOY_BACKEND )); then
  [[ -s "$NEW_JAR" ]] || die "缺少后端产物 $NEW_JAR"
fi
if (( DEPLOY_FRONTEND )); then
  [[ -f "$NEW_DIST/index.html" ]] || die "缺少前端产物 $NEW_DIST/index.html"
fi

# 走到这里必定是 root（见上文自动提权），无需 sudo -n
RESTART_CMD=(systemctl restart "$SERVICE")

SNAPSHOT="$BACKUP_DIR/$(date +%Y%m%d-%H%M%S)-$$"   # 带 PID，避免同秒并发撞名
mkdir -p "$SNAPSHOT"

show_startup_logs() {
  # 只在交互终端显示应用日志，避免把生产日志写入 CI 输出。
  [[ -t 2 ]] || return 0

  journalctl -u "$SERVICE" -n 30 --no-pager >&2 || true
  if [[ -r "$APP_LOG" ]]; then
    printf '\n--- %s ---\n' "$APP_LOG" >&2
    tail -n 60 "$APP_LOG" >&2 || true
  fi
}

rollback() {
  log "部署失败，开始回滚到上一版"
  if (( DEPLOY_BACKEND )) && [[ -f "$SNAPSHOT/app.jar" ]]; then
    cp -p "$SNAPSHOT/app.jar" "$APP_DIR/$JAR_NAME"
  fi
  if (( DEPLOY_FRONTEND )); then
    rm -rf "$STAGE"
    rm -rf "$WEB_DIR"
    if [[ -d "$OLD_WEB" ]]; then
      mv "$OLD_WEB" "$WEB_DIR"
    elif [[ -d "$SNAPSHOT/dist" ]]; then
      cp -a "$SNAPSHOT/dist" "$WEB_DIR"
    fi
  fi
  if (( DEPLOY_BACKEND )); then
    "${RESTART_CMD[@]}" || true
  fi
  die "已回滚。应用日志：sudo tail -n 100 $APP_LOG；服务日志：sudo journalctl -u $SERVICE -n 50 --no-pager"
}

# ---------- 1. 备份当前版本 ----------
if (( DEPLOY_BACKEND )) && [[ -f "$APP_DIR/$JAR_NAME" ]]; then
  cp -p "$APP_DIR/$JAR_NAME" "$SNAPSHOT/app.jar"
fi
if (( DEPLOY_FRONTEND )); then
  cp -a "$WEB_DIR" "$SNAPSHOT/dist"
fi
log "已备份到 $SNAPSHOT"

# ---------- 2. 替换后端 jar ----------
if (( DEPLOY_BACKEND )); then
  cp "$NEW_JAR" "$APP_DIR/$JAR_NAME.new"
  # JDK 自带 jar 命令，校验产物不是半截文件 / 文本错误页
  if command -v jar >/dev/null 2>&1; then
    jar tf "$APP_DIR/$JAR_NAME.new" >/dev/null 2>&1 || { rm -f "$APP_DIR/$JAR_NAME.new"; rollback; }
  fi
  mv -f "$APP_DIR/$JAR_NAME.new" "$APP_DIR/$JAR_NAME"
  log "后端 jar 已替换"
fi

# ---------- 3. 原子替换前端 dist（整目录改名，避免逐个删除造成白屏） ----------
if (( DEPLOY_FRONTEND )); then
  rm -rf "$STAGE" "$OLD_WEB"
  mkdir -p "$STAGE"
  cp -a "$NEW_DIST"/. "$STAGE"/
  [[ -f "$STAGE/index.html" ]] || rollback
  # dist 整目录替换时需要重建 ACME 路径，并让 Ubuntu 的 Nginx worker 可读取静态文件。
  mkdir -p "$STAGE/.well-known/acme-challenge"
  chown -hR root:www-data "$STAGE"
  find "$STAGE" -type d -exec chmod 0750 {} +
  find "$STAGE" -type f -exec chmod 0640 {} +
  runuser -u www-data -- test -r "$STAGE/index.html" \
    || die "Nginx 用户 www-data 无法读取 $STAGE/index.html，请检查目录权限"
  mv "$WEB_DIR" "$OLD_WEB"
  mv "$STAGE" "$WEB_DIR" || rollback
  rm -rf "$OLD_WEB"
  log "前端 dist 已替换"
fi

# ---------- 4. 重启 + 健康检查 ----------
if (( DEPLOY_BACKEND )); then
  log "重启 $SERVICE ..."
  "${RESTART_CMD[@]}" || { show_startup_logs; rollback; }

  # /api/** 被 AuthInterceptor 拦截，未登录返回 401 属正常；
  # 200/401/403/404 都说明「后端已起来且鉴权生效」，不能用 curl -f 判断。
  CODE=""
  for _ in $(seq 1 30); do
    CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$HEALTH_URL" || true)
    case "$CODE" in 200|401|403|404) break ;; esac
    sleep 2
  done
  case "$CODE" in
    200|401|403|404) log "后端健康检查通过（HTTP $CODE）" ;;
    *)
      log "健康检查未通过（HTTP ${CODE:-无响应}）"
      show_startup_logs
      rollback
      ;;
  esac
fi

# ---------- 5. 清理旧备份 ----------
ls -1dt "$BACKUP_DIR"/*/ 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -rf || true

log "部署完成：后端=$DEPLOY_BACKEND 前端=$DEPLOY_FRONTEND，备份=$SNAPSHOT"
if (( DEPLOY_BACKEND )); then
  systemctl is-active "$SERVICE" --no-pager || true
fi
