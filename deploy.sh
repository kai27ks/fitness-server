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
#
# CI / 本地先把产物放到服务器的 ~/incoming/：
#   scp target/app.jar app@<IP>:~/incoming/app.jar
#   scp -r ../vue3-jianshenfang/dist/* app@<IP>:~/incoming/dist/
#   ssh app@<IP> "/opt/fitness/deploy.sh"
#
# 首次部署前置（一次性）：
#   sudo scp deploy.sh app@<IP>:/opt/fitness/deploy.sh
#   sudo chown app:app /opt/fitness/deploy.sh && sudo chmod 750 /opt/fitness/deploy.sh
#   # app 用户免密重启服务，否则 CI 里 sudo 会卡在输密码：
#   sudo visudo  →  app ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart fitness
#
# 设计约定：
#   - 构建在 CI / 开发机做，服务器只做「换包 + 重启」，避免 2C4G 上跑 Maven/npm 导致 OOM
#   - 自动备份上一版 jar 与 dist，健康检查失败自动回滚
#   - 不动 application-prod.yml（600）、schema.sql、upload/、数据库
#   - 生产 sql.init.mode=never，表结构变更必须单独评审执行，本脚本绝不自动建表
# ==============================================================================
set -Eeuo pipefail

APP_DIR=/opt/fitness
WEB_DIR=/var/www/fitness
BACKUP_DIR=/var/backups/fitness/deploy
SERVICE=fitness
JAR_NAME=app.jar
HEALTH_URL=http://127.0.0.1:8080/api/courses
KEEP=3                       # 保留最近几份备份，防止磁盘涨满

INCOMING="${INCOMING_DIR:-$HOME/incoming}"
DEPLOY_BACKEND=1
DEPLOY_FRONTEND=1

log()  { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
die()  { printf '[%s] ERROR: %s\n' "$(date '+%F %T')" "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend-only)  DEPLOY_FRONTEND=0; shift ;;
    --frontend-only) DEPLOY_BACKEND=0;  shift ;;
    --incoming)      [[ $# -ge 2 ]] || die "--incoming 缺少路径"; INCOMING="$2"; shift 2 ;;
    -h|--help)       sed -n '2,32p' "$0"; exit 0 ;;
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

# root 直接调 systemctl；app 用户走 sudo -n（需上面 visudo 授权）
if [[ $EUID -eq 0 ]]; then
  RESTART_CMD=(systemctl restart "$SERVICE")
else
  RESTART_CMD=(sudo -n systemctl restart "$SERVICE")
fi

SNAPSHOT="$BACKUP_DIR/$(date +%Y%m%d-%H%M%S)-$$"   # 带 PID，避免同秒并发撞名
mkdir -p "$SNAPSHOT"

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
  die "已回滚，请检查日志：sudo journalctl -u $SERVICE -n 50 --no-pager"
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
  mv "$WEB_DIR" "$OLD_WEB"
  mv "$STAGE" "$WEB_DIR" || rollback
  rm -rf "$OLD_WEB"
  # dist 是整目录原子替换，会把 ACME 验证目录一起冲掉；
  # 这里重建，避免以后 certbot 申请/续期时 404。
  mkdir -p "$WEB_DIR/.well-known/acme-challenge"
  log "前端 dist 已替换"
fi

# ---------- 4. 重启 + 健康检查 ----------
if (( DEPLOY_BACKEND )); then
  log "重启 $SERVICE ..."
  "${RESTART_CMD[@]}" || rollback

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
    *) log "健康检查未通过（HTTP ${CODE:-无响应}）"; rollback ;;
  esac
fi

# ---------- 5. 清理旧备份 ----------
ls -1dt "$BACKUP_DIR"/*/ 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -rf || true

log "部署完成：后端=$DEPLOY_BACKEND 前端=$DEPLOY_FRONTEND，备份=$SNAPSHOT"
if (( DEPLOY_BACKEND )); then
  systemctl is-active "$SERVICE" --no-pager || true
fi
