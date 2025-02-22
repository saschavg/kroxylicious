#!/usr/bin/env bash
#
# Copyright Kroxylicious Authors.
#
# Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
#

set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
. "${SCRIPT_DIR}/common.sh"
# Tmp fix - revert the nounset change made by common to match the expectations of this script.
set +u

REPOSITORY="origin"
BRANCH_FROM="main"
DRY_RUN="false"
SKIP_VALIDATION="false"
TEMPORARY_RELEASE_BRANCH=""
PREPARE_DEVELOPMENT_BRANCH=""
ORIGINAL_GH_DEFAULT_REPO=""
while getopts ":v:b:k:r:n:dsh" opt; do
  case $opt in
    v) RELEASE_VERSION="${OPTARG}"
    ;;
    b) BRANCH_FROM="${OPTARG}"
    ;;
    r) REPOSITORY="${OPTARG}"
    ;;
    k) GPG_KEY="${OPTARG}"
      if [[ -z "${GPG_KEY}" ]]; then
          echo "GPG_KEY not set unable to sign the release. Please specify -k <YOUR_GPG_KEY>" 1>&2
          exit 1
      fi
    ;;
    n) DEVELOPMENT_VERSION="${OPTARG}"
    ;;
    d) DRY_RUN="true"
    ;;
    s) SKIP_VALIDATION="true"
    ;;
    h)
      1>&2 cat << EOF
usage: $0 -k keyid -v version [-b branch] [-r repository] [-s] [-d] [-h]
 -k short key id used to sign the release
 -v version number e.g. 0.3.0
 -b branch to release from (defaults to 'main')
 -n snapshot index to increment when opening main for new development (defaults to '2')
 -r the remote name of the kroxylicious repository (defaults to 'origin')
 -s skips validation
 -d dry-run mode
 -h this help message
EOF
      exit 1
    ;;
    \?) echo "Invalid option -$opt ${OPTARG}" >&2
    exit 1
    ;;
  esac

done

if [[ -z "${GPG_KEY}" ]]; then
    echo "GPG_KEY not set unable to sign the release. Please specify -k <YOUR_GPG_KEY>" 1>&2
    exit 1
fi

if [[ -z ${RELEASE_VERSION} ]]; then
  echo "No version specified, aborting"
  exit 1
fi

GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

ORIGINAL_WORKING_BRANCH=$(git branch --show-current)

cleanup() {
    if [[ -n ${ORIGINAL_WORKING_BRANCH} ]]; then
        git checkout "${ORIGINAL_WORKING_BRANCH}" || true
    fi

    if [[ ${ORIGINAL_GH_DEFAULT_REPO} ]]; then
      gh repo set-default ${ORIGINAL_GH_DEFAULT_REPO}
    fi

    if [[ ${RELEASE_TAG} ]]; then
      git tag --delete "${RELEASE_TAG}" || true
    fi

    # Note that git branch -D echos the sha of the deleted branch to
    # stdout.  This is great for debugging the release process as it
    # lets the developer restore to the state of the tree.
    if [[ "${DRY_RUN:-false}" == true && ${TEMPORARY_RELEASE_BRANCH} ]]; then
        git branch -D "${TEMPORARY_RELEASE_BRANCH}" || true
    fi

    if [[ "${DRY_RUN:-false}" == true && ${PREPARE_DEVELOPMENT_BRANCH} ]]; then
        git branch -D "${PREPARE_DEVELOPMENT_BRANCH}" || true
    fi
}

setVersion() {
  local VERSION=$1
  mvn -q -B versions:set -DnewVersion="${VERSION}" -DgenerateBackupPoms=false -DprocessAllModules=true

  # Bump version ref in files not controlled by Maven
  # shellcheck disable=SC2046
  ${SED} -i -e "s#${CURRENT_VERSION//./\\.}#${VERSION}#g" $(find kubernetes-examples -name "*.yaml" -type f)

  git add '**/*.yaml' '**/pom.xml' 'pom.xml'
}

trap cleanup EXIT

git stash --all
echo "Creating release branch from ${BRANCH_FROM}"
git fetch -q "${REPOSITORY}"
CURRENT_VERSION=$(mvn org.apache.maven.plugins:maven-help-plugin:3.4.0:evaluate -Dexpression=project.version -q -DforceStdout)

RELEASE_DATE=$(date -u '+%Y-%m-%d')
TEMPORARY_RELEASE_BRANCH="prepare-release-${RELEASE_DATE}"
git checkout -b "prepare-release-${RELEASE_DATE}" "${REPOSITORY}/${BRANCH_FROM}"

if [[ "${DRY_RUN:-false}" == true ]]; then
    DEPLOY_DRY_RUN_DIR=$(mktemp -d)
    #Disable the shell check as the colour codes only work with interpolation.
    # shellcheck disable=SC2059
    printf "${BLUE}Dry-run mode:${NC} no remote tags or PRs will be created, artefacts will be deployed to: ${DEPLOY_DRY_RUN_DIR}\n"
    MVN_DEPLOY_DRYRUN="-DaltDeploymentRepository=ossrh::file:${DEPLOY_DRY_RUN_DIR}"
else
    MVN_DEPLOY_DRYRUN=""
fi

if [[ "${SKIP_VALIDATION:-false}" != true ]]; then
    printf "Validating the build is ${GREEN}green${NC}"
    mvn -q clean verify
fi

echo "Versioning Kroxylicious as ${RELEASE_VERSION}"
setVersion "${RELEASE_VERSION}"
#Set the release version in the Changelog
${SED} -i -e "s_##\sSNAPSHOT_## ${RELEASE_VERSION//./\\.}_g" CHANGELOG.md
git add 'CHANGELOG.md'

echo "Validating things still build"
mvn -q -B clean install -Pquick

RELEASE_TAG="v${RELEASE_VERSION}"

echo "Committing release to git"
git commit --message "Release version ${RELEASE_TAG}" --signoff

git tag -f "${RELEASE_TAG}"

git push "${REPOSITORY}" "${RELEASE_TAG}" ${GIT_DRYRUN:-}

echo "Deploying release"

# shellcheck disable=SC2086
# Quoting leads to an extra space which causes maven to barf!
mvn -q -B -Prelease,dist -DskipTests=true -DreleaseSigningKey="${GPG_KEY}" ${MVN_DEPLOY_DRYRUN} -DprocessAllModules=true deploy

echo "Release deployed, preparing for development of ${DEVELOPMENT_VERSION}"
PREPARE_DEVELOPMENT_BRANCH="prepare-development-${RELEASE_DATE}"
git checkout -b "${PREPARE_DEVELOPMENT_BRANCH}" "${TEMPORARY_RELEASE_BRANCH}"

setVersion "${DEVELOPMENT_VERSION}"
# bump the Changelog to the next SNAPSHOT version. We do it this way so the changelog has the new release as the first entry
${SED} -i -e "s_##\s${RELEASE_VERSION//./\\.}_## SNAPSHOT\n## ${RELEASE_VERSION//./\\.}_g" CHANGELOG.md

git add 'CHANGELOG.md'
git commit --message "Start next development version" --signoff

if [[ "${DRY_RUN:-false}" == true ]]; then
    exit 0
fi

if ! command -v gh &> /dev/null
then
    echo "gh command could not be found. Please create a pull request by hand https://github.com/kroxylicious/kroxylicious/compare"
    exit
fi

ORIGINAL_GH_DEFAULT_REPO=$(gh repo set-default -v | (grep -v 'no default repository' || true))
gh repo set-default "$(git remote get-url "${REPOSITORY}")"

# create GitHub release via CLI https://cli.github.com/manual/gh_release_create
# it is created as a draft, the deploy_release workflow will publish it.
echo "Creating draft release notes."
gh release create "${RELEASE_TAG}" ./kroxylicious-*/target/kroxylicious-*-bin.* --title "${RELEASE_TAG}" --notes-file "CHANGELOG.md" --draft

BODY="Release version ${RELEASE_VERSION}"

# Workaround https://github.com/cli/cli/issues/2691
git push "${REPOSITORY}" HEAD

echo "Creating pull request to merge the released version."
gh pr create --head "${PREPARE_DEVELOPMENT_BRANCH}" --base "${BRANCH_FROM}" --title "Kroxylicious development version ${RELEASE_DATE}" --body "${BODY}" --repo "$(gh repo set-default -v)"
