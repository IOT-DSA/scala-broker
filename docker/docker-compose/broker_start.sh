#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

THIS_DIR=$( pwd )
while [[ $# -gt 0 ]]
do
key="$1"

REBUILD="false"

case $key in
    --sbt)
    SBT_PATH="$( cd $2; pwd -P )"
    shift # past argument
    shift # past value
    ;;
    -r|--rebuild)
    REBUILD="$2"
    shift # past argument
    shift # past value
    ;;
    *)    # unknown option
    shift # past argument
    ;;
esac
done

DOCKER_CMD='docker-compose up --force-recreate'

echo "search project version in $SBT_PATH/build.sbt" 


cd ${SBT_PATH}
VERSION=$( find '.' -name "build.sbt" |
    head -n1 |
    xargs grep '[ \t]*val APP_VERSION =' |
    head -n1 |
    sed 's/.*"\(.*\)".*/\1/' )

cd ${THIS_DIR}
echo "project version = $VERSION"  
echo "changing project version in docker-compose.yaml"
sed -i'.original' 's|image: iotdsa/broker-scala:.*|image: iotdsa/broker-scala:'${VERSION}'|g' "docker-compose.yaml"

if [ "$REBUILD" = true ] ; then

	DOCKER_CMD="$DOCKER_CMD --build"

	cd ${SBT_PATH}

	echo "rebuilding sbt project"
	sbt clean package docker:publishLocal
fi
cd ${THIS_DIR}
echo "starting docker containers"
bash -c ${DOCKER_CMD}