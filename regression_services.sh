#!/usr/bin/env bash
cd "`dirname "$0"`"
configFile="./configs/$1"
echo $configFile

eval $(ssh-agent)
ssh-add /home/ubuntu/.ssh/regression_rsa

#cd ../..
#cd services-hedera
#mvn -DskipTests clean install
#
cd ../swirlds-platform/
git pull
git submodule update --init --merge
mvn -DskipTests clean deploy

cd regression
export aws_access_key_id=AKIAJJST62PF5EO3CMAQ
export aws_secret_access_key=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
java \
-Daws.accessKeyId=AKIAJJST62PF5EO3CMAQ \
-Daws.secretKey=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 \
-Dlog4j.configurationFile=log4j2-jrs.xml \
-Dspring.output.ansi.enabled=ALWAYS \
-jar regression.jar "$configFile"