#!/bin/bash

# CATXI 환경변수 설정 (보안 강화)
export DB_HOST="${DB_HOST}"
export DB_PORT="${DB_PORT}"
export DB_USER="${DB_USER}"
export DB_PW="${DB_PW}"
export REDIS_HOST="${REDIS_HOST}"
export REDIS_PORT="${REDIS_PORT}"
export REDIS_PW="${REDIS_PW}"

# 시간대 설정 추가
export TZ=Asia/Seoul

echo "CATXI Environment variables set"
echo "Timezone set to: $(date)"

# 설정 (절대경로 사용)
APP_DIR="/home/deploy/app"
ARCHIVE_PATH=$(find "$APP_DIR" -name "*.tar.gz" | head -n 1)
EXTRACT_DIR="$APP_DIR/extracted"
LOG_FILE="$APP_DIR/app.log"
PID_FILE="$APP_DIR/app.pid"

# tar.gz가 없으면 종료
if [ ! -f "$ARCHIVE_PATH" ]; then
  echo "No .tar.gz file found in $APP_DIR."
  exit 1
fi

# 기존 프로세스 중지 (개선)
if [ -f "$PID_FILE" ]; then
  PID=$(cat "$PID_FILE")
  if ps -p $PID > /dev/null; then
    echo "Stopping existing process with PID $PID..."
    kill $PID
    sleep 5
    
    # 강제 종료 확인
    if ps -p $PID > /dev/null; then
      echo "Force killing process..."
      kill -9 $PID
      sleep 2
    fi
  fi
  rm -f "$PID_FILE"  # PID 파일 삭제
fi

# 추가 프로세스 정리
pkill -f "java.*jar" 2>/dev/null || true
sleep 2

# 압축 해제 폴더 정리
rm -rf "$EXTRACT_DIR"
mkdir -p "$EXTRACT_DIR"

# 압축 해제
echo "Extracting $ARCHIVE_PATH..."
tar -xzf "$ARCHIVE_PATH" -C "$EXTRACT_DIR"

# JAR 파일 찾기 (개선)
JAR_FILE=$(find "$EXTRACT_DIR" -name "*.jar" | head -n 1)
if [ -z "$JAR_FILE" ]; then
  echo "No .jar file found after extraction."
  exit 1
fi

# 애플리케이션 실행 (시간대 설정 포함)
echo "Starting CATXI application: $JAR_FILE"
nohup java -jar "$JAR_FILE" \
  -Duser.timezone=Asia/Seoul \
  --spring.profiles.active=$1 > "$LOG_FILE" 2>&1 &

# PID 저장
echo $! > "$PID_FILE"
PID=$(cat "$PID_FILE")
echo "Application started with PID $PID at $(date)"

# 시작 확인 (추가)
sleep 15
if ps -p $PID > /dev/null 2>&1; then
  echo "✅ CATXI Application is running successfully!"
else
  echo "❌ Application failed to start. Check logs: $LOG_FILE"
  exit 1
fi

