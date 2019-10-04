FROM alpine:latest

RUN apk update
RUN apk add bash
RUN apk add socat

COPY event_script.txt event_script.txt
COPY run_script.sh run_script.sh

ENTRYPOINT ["/run_script.sh"]
