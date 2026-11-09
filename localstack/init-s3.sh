#!/bin/sh
# Creates the private case-file bucket when LocalStack is ready (docker compose up localstack).
awslocal s3api create-bucket --bucket "${CROWDTRACE_STORAGE_S3_BUCKET:-crowdtrace-case-files}"
awslocal s3api put-public-access-block --bucket "${CROWDTRACE_STORAGE_S3_BUCKET:-crowdtrace-case-files}" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
