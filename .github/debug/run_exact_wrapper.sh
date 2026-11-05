#!/bin/bash
# 在 Actions runner 上原样复刻 uses: docker:// 生成的 docker run 命令，
# 只把 entrypoint 换成带打点的 python 脚本，用来定位静默退出点。
set -uo pipefail

echo "--- host env sanity ---"
echo "GITHUB_WORKSPACE=${GITHUB_WORKSPACE}"
echo "RUNNER_TEMP=${RUNNER_TEMP}"
echo "HOME=${HOME}"

docker run --rm \
  --workdir /github/workspace \
  -e "RESOLVED_MODEL" \
  -e "DISPLAY_MODEL" \
  -e "GITHUB_EVENT_NAME" \
  -e "HEAD_SHA" \
  -e "GITHUB_TOKEN" \
  -e "OPENAI_KEY" \
  -e "OPENAI.KEY" \
  -e "OPENAI.API_BASE" \
  -e "OPENAI__API_BASE" \
  -e "OPENAI_API_BASE" \
  -e "CONFIG.MODEL" \
  -e "CONFIG.FALLBACK_MODELS" \
  -e "CONFIG.CUSTOM_MODEL_MAX_TOKENS" \
  -e "CONFIG.MAX_MODEL_TOKENS" \
  -e "GITHUB_ACTION_CONFIG.AUTO_DESCRIBE" \
  -e "GITHUB_ACTION_CONFIG.AUTO_REVIEW" \
  -e "GITHUB_ACTION_CONFIG.AUTO_IMPROVE" \
  -e "PR_REVIEWER.EXTRA_INSTRUCTIONS" \
  -e "PR_CODE_SUGGESTIONS.EXTRA_INSTRUCTIONS" \
  -e "PR_DESCRIPTION.EXTRA_INSTRUCTIONS" \
  -e "HOME" \
  -e "GITHUB_JOB" \
  -e "GITHUB_REF" \
  -e "GITHUB_SHA" \
  -e "GITHUB_REPOSITORY" \
  -e "GITHUB_REPOSITORY_OWNER" \
  -e "GITHUB_REPOSITORY_OWNER_ID" \
  -e "GITHUB_RUN_ID" \
  -e "GITHUB_RUN_NUMBER" \
  -e "GITHUB_RETENTION_DAYS" \
  -e "GITHUB_RUN_ATTEMPT" \
  -e "GITHUB_ACTOR_ID" \
  -e "GITHUB_ACTOR" \
  -e "GITHUB_WORKFLOW" \
  -e "GITHUB_HEAD_REF" \
  -e "GITHUB_BASE_REF" \
  -e "GITHUB_SERVER_URL" \
  -e "GITHUB_API_URL" \
  -e "GITHUB_GRAPHQL_URL" \
  -e "GITHUB_REF_NAME" \
  -e "GITHUB_REF_PROTECTED" \
  -e "GITHUB_REF_TYPE" \
  -e "GITHUB_WORKFLOW_REF" \
  -e "GITHUB_WORKFLOW_SHA" \
  -e "GITHUB_REPOSITORY_ID" \
  -e "GITHUB_TRIGGERING_ACTOR" \
  -e "GITHUB_WORKSPACE" \
  -e "GITHUB_ACTION" \
  -e "GITHUB_EVENT_PATH" \
  -e "GITHUB_ACTION_REPOSITORY" \
  -e "GITHUB_ACTION_REF" \
  -e "GITHUB_PATH" \
  -e "GITHUB_ENV" \
  -e "GITHUB_STEP_SUMMARY" \
  -e "GITHUB_STATE" \
  -e "GITHUB_OUTPUT" \
  -e "GITHUB_ARTIFACTS" \
  -e "GITHUB_ARTIFACTS_LIST" \
  -e "RUNNER_OS" \
  -e "RUNNER_ARCH" \
  -e "RUNNER_NAME" \
  -e "RUNNER_ENVIRONMENT" \
  -e "RUNNER_TOOL_CACHE" \
  -e "RUNNER_TEMP" \
  -e "RUNNER_WORKSPACE" \
  -e "ACTIONS_RUNTIME_URL" \
  -e "ACTIONS_RUNTIME_TOKEN" \
  -e "ACTIONS_CACHE_URL" \
  -e "ACTIONS_RESULTS_URL" \
  -e "ACTIONS_CACHE_MODE" \
  -e "ACTIONS_ORCHESTRATION_ID" \
  -e GITHUB_ACTIONS=true \
  -e CI=true \
  -v "/var/run/docker.sock":"/var/run/docker.sock" \
  -v "${RUNNER_TEMP}":"/github/runner_temp" \
  -v "${RUNNER_TEMP}/_github_home":"/github/home" \
  -v "${RUNNER_TEMP}/_github_workflow":"/github/workflow" \
  -v "${RUNNER_TEMP}/_runner_file_commands":"/github/file_commands" \
  -v "${GITHUB_WORKSPACE}":"/github/workspace" \
  --entrypoint python \
  "${DIAG_IMAGE}" -u /github/workspace/.github/debug/diag_pr_agent.py
echo "EXACT_WRAPPER_EXIT=$?"
