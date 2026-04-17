#!/bin/bash

MVN_OPTS="-Duser.home=/var/maven -T 4"

if [ -z ${USER_UID:+x} ]
then
  export USER_UID=1000
  export GROUP_GID=1000
fi

clean () {
  docker compose run --rm maven mvn $MVN_OPTS clean
}

install () {
  docker compose run --rm maven mvn $MVN_OPTS install -DskipTests -U
}

test () {
  docker compose run --rm maven mvn $MVN_OPTS test
}

publish() {
  version=`docker compose run --rm maven mvn $MVN_OPTS help:evaluate -Dexpression=project.version -q -DforceStdout`
  level=`echo $version | cut -d'-' -f3`
  case "$level" in
    *SNAPSHOT) export nexusRepository='snapshots' ;;
    *)         export nexusRepository='releases' ;;
  esac

  docker compose run --rm  maven mvn -DrepositoryId=ode-$nexusRepository -DskipTests --settings /var/maven/.m2/settings.xml deploy
}

# If DEBUG env var is set to "true" then set -x to enable debug mode
if [ "$DEBUG" == "true" ]; then
	set -x
	EDIFICE_CLI_DEBUG_OPTION="--debug"
else
	EDIFICE_CLI_DEBUG_OPTION=""
fi

init() {
  me=`id -u`:`id -g`
  echo "DEFAULT_DOCKER_USER=$me" > .env

  # If CLI_VERSION is empty set to latest
  if [ -z "$CLI_VERSION" ]; then
    CLI_VERSION="latest"
  fi
  # Create a build.compose.yaml file from following template
  cat <<EOF > build.compose.yaml
services:
  edifice-cli:
    image: opendigitaleducation/edifice-cli:$CLI_VERSION
    user: "$DEFAULT_DOCKER_USER"
EOF
  # Copy /root/edifice from edifice-cli container to host machine
  docker compose -f build.compose.yaml create edifice-cli
  docker compose -f build.compose.yaml cp edifice-cli:/root/edifice ./edifice
  docker compose -f build.compose.yaml rm -fsv edifice-cli
  rm -f build.compose.yaml
  chmod +x edifice
  ./edifice version $EDIFICE_CLI_DEBUG_OPTION
}

if [ ! -e .env ]; then
  init
fi

for param in "$@"
do
  case $param in
    init)
      init
      ;;
    clean)
      clean
      ;;
    install)
      install
      ;;
    test)
      test
      ;;
    publish)
      publish
      ;;
    *)
      echo "Invalid argument : $param"
  esac
  if [ ! $? -eq 0 ]; then
    exit 1
  fi
done

