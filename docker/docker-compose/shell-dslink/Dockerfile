FROM google/dart:latest
RUN curl https://raw.githubusercontent.com/IOT-DSA/dslink-dart-shell/master/tool/install.sh > install.sh && bash install.sh; rm install.sh
ENV BROKER_HOST '127.0.0.1'
ENV BROKER_PORT '8080'
COPY ./wait-for-it.sh .
ENTRYPOINT ["sh", "-c", "./wait-for-it.sh $BROKER_HOST:$BROKER_PORT -- /usr/bin/dart /root/.dsash/shell/bin/run.dart --broker https://$BROKER_HOST:$BROKER_PORT/conn"]
