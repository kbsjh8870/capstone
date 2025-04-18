#!/usr/bin/env bash

PROJECT_ROOT="/home/ubuntu/app"
BUILD_JAR=$(ls $PROJECT_ROOT/build/libs/*.jar | head -n 1)
JAR_FILE="$PROJECT_ROOT/capstone.jar"

APP_LOG="$PROJECT_ROOT/application.log"
ERROR_LOG="$PROJECT_ROOT/error.log"
DEPLOY_LOG="$PROJECT_ROOT/deploy.log"

TIME_NOW=$(date +%c)

echo "$TIME_NOW > JAR 복사: $BUILD_JAR → $JAR_FILE" >> $DEPLOY_LOG
cp $BUILD_JAR $JAR_FILE

echo "$TIME_NOW > JAR 실행" >> $DEPLOY_LOG
nohup java -jar $JAR_FILE > $APP_LOG 2> $ERROR_LOG &

CURRENT_PID=$(pgrep -f $JAR_FILE)
echo "$TIME_NOW > 실행된 프로세스 아이디 $CURRENT_PID 입니다." >> $DEPLOY_LOG
