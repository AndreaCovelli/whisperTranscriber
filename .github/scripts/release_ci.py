"""Reuse completed main CI checks before verifying and publishing a release."""

import json
import os
import re
import time
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

# These job and step names form the release verification contract with ci.yml.
CHECKS = {
    "quality-build": (
        "Quality & Build",
        {"Run quality checks", "Build debug APK and run JVM tests"},
    ),
    "debug": (
        "Instrumentation (debug)",
        {"Verify notification permission recreation", "Run managed-device tests"},
    ),
    "minifiedSmoke": (
        "Instrumentation (minifiedSmoke)",
        {"Verify minified JSON exports", "Require minified export test results"},
    ),
    "security": (
        "Security",
        {
            "Scan new commits for secrets",
            "Generate release runtime CycloneDX SBOM",
            "Report release runtime SBOM vulnerabilities",
            "Gate fixed high and critical release runtime vulnerabilities",
        },
    ),
}


def records(get, path, key):
    page = 1
    separator = "&" if "?" in path else "?"
    while True:
        batch = get(f"{path}{separator}per_page=100&page={page}")[key]
        yield from batch
        if len(batch) < 100:
            return
        page += 1


def reusable_checks(jobs):
    completed = set()
    for check, (name, required_steps) in CHECKS.items():
        matches = [job for job in jobs if job["name"] == name]
        if not matches:
            continue
        if len(matches) != 1:
            raise RuntimeError(f"Ambiguous CI results for {name}")
        job = matches[0]
        if job["conclusion"] not in ("success", "skipped", "neutral", None):
            raise RuntimeError(f"Required CI job {name} ended with {job['conclusion']}")
        if job["status"] != "completed" or job["conclusion"] != "success":
            continue
        for step in job["steps"]:
            if step["name"] in required_steps and step["conclusion"] not in (
                "success",
                "skipped",
                "neutral",
                None,
            ):
                raise RuntimeError(
                    f"Required CI step {step['name']} ended with {step['conclusion']}"
                )
        successful_steps = {
            step["name"]
            for step in job["steps"]
            if step["status"] == "completed" and step["conclusion"] == "success"
        }
        if required_steps <= successful_steps:
            completed.add(check)
    return completed


def verification_outputs(completed):
    return {
        "required": str(completed != set(CHECKS)).lower(),
        "skip-jobs": json.dumps(
            [check for check in ("quality-build", "security") if check in completed]
        ),
        "instrumentation-suites": json.dumps(
            [suite for suite in ("debug", "minifiedSmoke") if suite not in completed]
        ),
    }


def plan_verification(
    get,
    repository,
    commit,
    *,
    clock=time.monotonic,
    sleep=time.sleep,
    discovery_seconds=60,
    poll_seconds=30,
):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Release verification requires a full commit SHA")
    prefix = f"repos/{repository}/actions"
    query = urlencode({"head_sha": commit, "branch": "main", "event": "push"})
    path = f"{prefix}/workflows/ci.yml/runs?{query}"
    started = clock()

    while True:
        runs = [
            run
            for run in records(get, path, "workflow_runs")
            if run["head_sha"] == commit
            and run["head_branch"] == "main"
            and run["event"] == "push"
            and run["path"] == ".github/workflows/ci.yml"
            and run["repository"]["full_name"].lower() == repository.lower()
        ]
        if runs:
            run = max(runs, key=lambda candidate: candidate["id"])
            # Fail fast instead of spending runner minutes waiting on CI.
            if run["status"] != "completed":
                raise RuntimeError(
                    f"Main CI is still {run['status']}: {run['html_url']}; "
                    "re-push the tag after it succeeds"
                )
            if run["conclusion"] != "success":
                raise RuntimeError(
                    f"Main CI ended with {run['conclusion']}: {run['html_url']}"
                )
            jobs = list(
                records(get, f"{prefix}/runs/{run['id']}/jobs?filter=latest", "jobs")
            )
            current = get(f"{prefix}/runs/{run['id']}")
            if current["status"] != "completed":
                raise RuntimeError(
                    f"Main CI was rerun and is still {current['status']}: "
                    f"{run['html_url']}; re-push the tag after it succeeds"
                )
            if current["conclusion"] != "success":
                raise RuntimeError(
                    f"Main CI ended with {current['conclusion']}: {run['html_url']}"
                )
            # A completed rerun replaced the fetched job results, so inspect it again.
            if current["run_attempt"] != run["run_attempt"]:
                continue
            completed = reusable_checks(jobs)
            print(
                f"Reusing {', '.join(sorted(completed)) or 'no checks'} from {run['html_url']}"
            )
            return verification_outputs(completed)

        if clock() - started >= discovery_seconds:
            print(f"No main CI run found for {commit}; requesting full verification.")
            return verification_outputs(set())
        print(f"Waiting for a main CI run on {commit} to appear...", flush=True)
        sleep(max(0, min(poll_seconds, discovery_seconds - (clock() - started))))


def main():
    api_url = os.environ["GITHUB_API_URL"].rstrip("/")

    def get(path):
        request = Request(
            f"{api_url}/{path}",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {os.environ['GH_TOKEN']}",
                "X-GitHub-Api-Version": "2026-03-10",
            },
        )
        with urlopen(request, timeout=30) as response:
            return json.load(response)

    outputs = plan_verification(
        get, os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_SHA"]
    )
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.writelines(f"{name}={value}\n" for name, value in outputs.items())


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, KeyError, URLError) as error:
        raise SystemExit(f"Release CI verification failed: {error}") from error
