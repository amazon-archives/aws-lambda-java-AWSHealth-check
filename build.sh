#!/bin/bash
docker build -t automate-lambda-java-awshealth-check-build .
docker run -d --name build-complete automate-lambda-java-awshealth-check-build
mkdir -p bin
docker cp build-complete:/build/target/AWSHealthCheck-1.0-SNAPSHOT.jar ${PWD}/bin
docker container rm build-complete
