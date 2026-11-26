@echo off
rem ============================================================================
rem 本机以 prod 配置启动后端（配置来自 config\application-prod.yml，不在 jar 内）
rem 用法：先 mvn -DskipTests package，再双击或命令行执行 start-prod.cmd
rem 改完 config\application-prod.yml 后 Ctrl+C 再跑一次即可生效，无需重新打包
rem ============================================================================
setlocal
cd /d "%~dp0"

set "JAR=target\app.jar"
if not exist "%JAR%" (
  echo [ERROR] 找不到 %JAR%，请先执行：mvn -DskipTests package
  exit /b 1
)
if not exist "config\application-prod.yml" (
  echo [ERROR] 找不到 config\application-prod.yml，外置配置缺失，拒绝启动
  exit /b 1
)

set "SPRING_CONFIG_ADDITIONAL_LOCATION=file:./config/"
set "TZ=Asia/Shanghai"

echo [INFO] 外置配置：%CD%\config\application-prod.yml
java -Xms512m -Xmx1536m -jar "%JAR%" --spring.profiles.active=prod %*

endlocal
