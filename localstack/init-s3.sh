#!/bin/sh
# Creates the private case-file bucket when LocalStack is ready (docker compose up localstack).
awslocal s3api create-bucket --bucket "${CROWDTRACE_STORAGE_S3_BUCKET:-crowdtrace-case-files}"
awslocal s3api put-public-access-block --bucket "${CROWDTRACE_STORAGE_S3_BUCKET:-crowdtrace-case-files}" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

# Report objects for the dev seed (src/main/resources/db/dev-seed): case ids 1000001-1000400
# each have a REPORT file at reports/00000000-0000-0000-0000-<caseId as 12 digits>. All point at the same sample PDF.
SEED_PDF=/etc/localstack/seed/sample-report.pdf
if [ -f "$SEED_PDF" ]; then
  dir=$(mktemp -d)
  mkdir -p "$dir/reports"
  for id in $(seq 1000001 1000400); do cp "$SEED_PDF" "$dir/reports/$(printf "00000000-0000-0000-0000-%012d" "$id")"; done
  awslocal s3 cp "$dir" "s3://${CROWDTRACE_STORAGE_S3_BUCKET:-crowdtrace-case-files}/" \
    --recursive --content-type application/pdf --only-show-errors
  rm -rf "$dir"
fi
