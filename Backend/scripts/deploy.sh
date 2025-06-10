#!/usr/bin/env bash

PROJECT_ROOT="/home/ubuntu/app"
cd $PROJECT_ROOT

JAR_FILE="$PROJECT_ROOT/capstone.jar" 

DEPLOY_LOG="$PROJECT_ROOT/deploy.log"

TIME_NOW=$(date +%c)

# 현재 구동 중인 애플리케이션 pid 확인
CURRENT_PID=$(pgrep -f $JAR_FILE)

# 프로세스가 켜져 있으면 종료
if [ -z $CURRENT_PID ]; then
  echo "$TIME_NOW > 현재 실행중인 애플리케이션이 없습니다" >> $DEPLOY_LOG
else
  echo "$TIME_NOW > 실행중인 $CURRENT_PID 애플리케이션 종료 " >> $DEPLOY_LOG
  kill -9 $CURRENT_PID
fi

#!/bin/bash
BUILD_JAR=$(ls /home/ubuntu/app/build/libs/Backend-0.0.1-SNAPSHOT.jar)
JAR_NAME=$(basename $BUILD_JAR)

echo "> 현재 시간: $(date)" >> /home/ubuntu/app/deploy.log

echo "> build 파일명: $JAR_NAME" >> /home/ubuntu/app/deploy.log

echo "> build 파일 복사" >> /home/ubuntu/app/deploy.log
DEPLOY_PATH=/home/ubuntu/app/
cp $BUILD_JAR $DEPLOY_PATH

echo "> 현재 실행중인 애플리케이션 pid 확인" >> /home/ubuntu/app/deploy.log
CURRENT_PID=$(pgrep -f $JAR_NAME)

if [ -z $CURRENT_PID ]
then
  echo "> 현재 구동중인 애플리케이션이 없으므로 종료하지 않습니다." >> /home/ubuntu/app/deploy.log
else
  echo "> kill -9 $CURRENT_PID" >> /home/ubuntu/app/deploy.log
  sudo kill -9 $CURRENT_PID
  sleep 5
fi


DEPLOY_JAR=$DEPLOY_PATH$JAR_NAME
echo "> DEPLOY_JAR 배포"    >> /home/ubuntu/app/deploy.log
sudo nohup java -jar $DEPLOY_JAR \
  --tmap.api.key="vU7PQGq7eR8bMAnPeg6F285MVcSpXW9W7wNV57JZ" \
  --weather.api.key="a08be68225c2a67d6c20df36c2239922" \
  >> /home/ubuntu/app/deploy.log 2>/home/ubuntu/app/deploy_err.log &
