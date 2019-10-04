#!/bin/bash

echo "1" | socat -u - TCP:127.0.0.1:9099,keepalive,keepidle=10,ignoreeof,keepintvl=10,keepcnt=100 &
sleep 0.1s
echo "2" | socat -u - TCP:127.0.0.1:9099,keepalive,ignoreeof,keepidle=10,keepintvl=10,keepcnt=100 &
sleep 0.1s
echo "3" | socat -u - TCP:127.0.0.1:9099,keepalive,ignoreeof,keepidle=10,keepintvl=10,keepcnt=100 &

sleep 1s

(while read event; do
     echo $event
     sleep 0.1s
 done <event_script.txt
) | socat -u - TCP:127.0.0.1:9090,keepidle=10,keepintvl=10,keepcnt=100

