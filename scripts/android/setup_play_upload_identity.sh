#!/bin/bash

# Create the Google Cloud half of the keyless Play upload that the
# upload-to-play job in .github/workflows/android-release.yml runs: a service
# account with no Cloud role, a Workload Identity pool, a GitHub OIDC provider
# that admits only that job, and the binding that lets the provider impersonate
# the account. No service-account key is ever created. Run it once, in Google
# Cloud Shell or anywhere gcloud is logged in as the project owner:
#
#   bash scripts/android/setup_play_upload_identity.sh <project id or number>
#
# Each resource is described before it is created and the provider is updated
# in place, so a rerun converges instead of failing. The script grants no Play
# access: Play Console has no Cloud IAM role, so the printed service account
# must be invited there by hand, and the two printed GitHub variables set.

set -Eeuo pipefail

REPO="ppigazzini/r47zen"
WORKFLOW_PATH=".github/workflows/android-release.yml"
ENVIRONMENT="play-upload"
POOL_ID="github"
PROVIDER_ID="r47zen"
SERVICE_ACCOUNT_ID="r47zen-play-upload"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

[ "$#" -eq 1 ] || die "usage: $0 <Google Cloud project id or number>"
for tool in gcloud curl jq; do
    command -v "$tool" >/dev/null || die "$tool is not installed; Google Cloud Shell has all three."
done

project_id="$(gcloud projects describe "$1" --format='value(projectId)')" ||
    die "gcloud cannot read project $1; log in as its owner with gcloud auth login."
project_number="$(gcloud projects describe "$1" --format='value(projectNumber)')"
service_account="${SERVICE_ACCOUNT_ID}@${project_id}.iam.gserviceaccount.com"
pool_name="projects/${project_number}/locations/global/workloadIdentityPools/${POOL_ID}"
provider_name="${pool_name}/providers/${PROVIDER_ID}"

# Gate on GitHub's numeric ids, which GitHub never reuses: a name condition
# alone admits whoever registers ppigazzini/r47zen after a delete or rename.
repo_json="$(curl --fail --silent --show-error --location \
    --header 'Accept: application/vnd.github+json' "https://api.github.com/repos/${REPO}")"
repo_id="$(jq -er '.id' <<<"$repo_json")"
owner_id="$(jq -er '.owner.id' <<<"$repo_json")"

# Admit one job: the play-upload environment of the release workflow on main.
# The environment admits only main as well, so either gate alone holds.
condition="assertion.repository_owner_id == '${owner_id}'"
condition+=" && assertion.repository_id == '${repo_id}'"
condition+=" && assertion.environment == '${ENVIRONMENT}'"
condition+=" && assertion.ref == 'refs/heads/main'"
condition+=" && assertion.workflow_ref == '${REPO}/${WORKFLOW_PATH}@refs/heads/main'"
mapping="google.subject=assertion.sub"
mapping+=",attribute.repository_owner_id=assertion.repository_owner_id"
mapping+=",attribute.repository_id=assertion.repository_id"
mapping+=",attribute.environment=assertion.environment"
mapping+=",attribute.ref=assertion.ref"
mapping+=",attribute.workflow_ref=assertion.workflow_ref"

echo "== project ${project_id} (${project_number}), repository ${REPO} (id ${repo_id})"

echo "== enable the Play Developer, IAM, IAM Credentials, and STS APIs"
gcloud services enable --project "$project_id" \
    androidpublisher.googleapis.com \
    iam.googleapis.com \
    iamcredentials.googleapis.com \
    sts.googleapis.com

echo "== service account ${service_account}"
if ! gcloud iam service-accounts describe "$service_account" --project "$project_id" >/dev/null 2>&1; then
    gcloud iam service-accounts create "$SERVICE_ACCOUNT_ID" --project "$project_id" \
        --display-name "R47 Zen Play upload" \
        --description "Uploads release bundles to Google Play from the r47zen release workflow"
fi

echo "== workload identity pool ${POOL_ID}"
if ! gcloud iam workload-identity-pools describe "$POOL_ID" \
    --project "$project_id" --location global >/dev/null 2>&1; then
    gcloud iam workload-identity-pools create "$POOL_ID" \
        --project "$project_id" --location global \
        --display-name "GitHub Actions"
fi

echo "== GitHub OIDC provider ${PROVIDER_ID}"
provider_args=(
    "$PROVIDER_ID"
    --project "$project_id"
    --location global
    --workload-identity-pool "$POOL_ID"
    --display-name "r47zen release workflow"
    --issuer-uri "https://token.actions.githubusercontent.com"
    --attribute-mapping "$mapping"
    --attribute-condition "$condition"
)
if gcloud iam workload-identity-pools providers describe "$PROVIDER_ID" \
    --project "$project_id" --location global --workload-identity-pool "$POOL_ID" >/dev/null 2>&1; then
    gcloud iam workload-identity-pools providers update-oidc "${provider_args[@]}"
else
    gcloud iam workload-identity-pools providers create-oidc "${provider_args[@]}"
fi

# A service account created seconds ago can be invisible to IAM for a while,
# so the binding retries before it gives up.
echo "== let the provider impersonate ${service_account}"
member="principalSet://iam.googleapis.com/${pool_name}/attribute.repository_id/${repo_id}"
for attempt in 1 2 3 4 5 6; do
    if gcloud iam service-accounts add-iam-policy-binding "$service_account" \
        --project "$project_id" \
        --role roles/iam.workloadIdentityUser \
        --member "$member" >/dev/null; then
        break
    fi
    [ "$attempt" -lt 6 ] || die "could not bind ${member} to ${service_account}."
    echo "   binding failed, retrying in 10 s (attempt ${attempt} of 6)" >&2
    sleep 10
done

cat <<NEXT

Google Cloud is ready. Two steps remain:

1. Play Console, Users and permissions, Invite new users:
     ${service_account}
   App permissions: R47 Zen only, with "Release apps to testing tracks".
   Leave account permissions empty.

2. On a machine where gh is logged in to GitHub:
     gh variable set R47_PLAY_WIF_PROVIDER --env ${ENVIRONMENT} --repo ${REPO} --body ${provider_name}
     gh variable set R47_PLAY_SERVICE_ACCOUNT --env ${ENVIRONMENT} --repo ${REPO} --body ${service_account}
NEXT
