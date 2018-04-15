FROM google/dart:latest
COPY ./install.sh .
RUN bash install.sh; rm install.sh
ENV BROKER_HOST '127.0.0.1'
ENV BROKER_PORT '8080'
COPY ./wait-for-it.sh .
ENTRYPOINT ["sh", "-c", "./wait-for-it.sh $BROKER_HOST:$BROKER_PORT -- /usr/bin/dart /root/.system/system/bin/run.dart --broker http://$BROKER_HOST:$BROKER_PORT/conn"]