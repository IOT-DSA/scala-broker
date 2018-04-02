# Dev environment tools

This docker-compose build starts:
- broker instance
- shell dslink instance (requester) https://github.com/IOT-DSA/dslink-dart-shell
- weather dslink instance (responder) https://github.com/IOT-DSA/dslink-dart-weather
- system dslink instance (responder) https://github.com/IOT-DSA/dslink-dart-system

To start applications you should have docker + docker-compose installed

## Starting from script
(tested only on Mac OS)

```bash
# Rebuild project 'latest' docker image, publish it to local docker repo and start apps
./broker_start.sh --sbt ../../ --rebuild true
```
or without scala-broker rebuild

```bash
# Starts apps with 'latest' scala-broker image from local docker repo
./broker_start.sh --sbt ../../
```

## Running without script

in this case you should manually reconfigure scala-brocker image tag in docker-compose.yaml
(image: iotdsa/broker-scala:**0.4.0-SNAPSHOT**) 

```bash
# building fresh docker image of scala-brocker
cd <root folder of project with build.sbt file>
sbt clean docker:publishLocal

# starting apps
cd docker/docker-compose
docker-compose up
```

## Stopping and starting one of the dockers

```bash
# bstop shell service container
docker-compose stop shell

# start shell service container
docker-compose start shell

# restart shell service container
docker-compose restart shell
```

## Stop all instances 

```bash
# Stops all instances from this docker-compose file
docker-compose down
```

or to stop and remove all running dockers

```bash
# Stops all running dockers
docker rm -f $(docker ps -a -q)
```

## Using debug mode

you can use remote debug on 9005 port

## Using shell dslink

```bash
# Connecting to shell container
docker attach shell-dslink

# shell help command
help

# SUBJ
ls

cd downstream/System

ls 

# //TODO Some other commands to get dslink data, change it's state etc
```
    