#!/bin/bash
#Suggest deploying to us-east-1 due to SES
export AWS_DEFAULT_REGION=us-east-1
#Change the below, an s3 bucket to store lambda code for deploy
#Must be in same region as lambda (ie DEFAULT_REGION)
export CONFIG_BUCKET=changeme
export CONFIG_FILE=config.yaml
#Change below to set the save bucket for saving site fingerprint data.
export BUCKET=changeme

if [[ ! -f bin/AWSHealthCheck-1.0-SNAPSHOT.jar ]]; then
    echo "AWSHealthCheck-1.0-SNAPSHOT.jar not found! Run build.sh first."
    exit 1
fi

aws cloudformation package \
   --template-file src/sam.yaml \
   --output-template-file deploy.sam.yaml \
   --s3-bucket $CONFIG_BUCKET \
   --s3-prefix automate-lambda-java-AWSHealth-check-build
aws cloudformation deploy \
  --template-file deploy.sam.yaml \
  --stack-name automate-lambda-java-AWSHealth-check-build  \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides DEFAULTREGION=$DEFAULT_REGION CONFIGFILE=$CONFIG_FILE BUCKET=$BUCKET
