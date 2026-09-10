import io
import json
import os
import subprocess
import tempfile
import textwrap
import unittest
from contextlib import redirect_stdout
from copy import deepcopy
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit

from release_ci import main, plan_verification

REPOSITORY = "example/transcriber"
COMMIT = "a" * 40


def successful_run(**overrides):
    return {
        "id": 42,
        "run_attempt": 1,
        "head_sha": COMMIT,
        "head_branch": "main",
        "event": "push",
        "path": ".github/workflows/ci.yml",
        "repository": {"full_name": REPOSITORY},
        "status": "completed",
        "conclusion": "success",
        "html_url": "https://github.com/example/transcriber/actions/runs/42",
        **overrides,
    }


def job(name, *steps, conclusion="success"):
    return {
        "name": name,
        "status": "completed",
        "conclusion": conclusion,
        "steps": [
            {"name": step, "status": "completed", "conclusion": "success"}
            for step in steps
        ],
    }


def successful_jobs():
    return [
        job("Quality", "Run quality checks"),
        job("Build & Unit", "Build debug APK and run JVM tests"),
        job(
            "Instrumentation (debug)",
            "Verify notification permission recreation",
            "Run managed-device tests",
        ),
        job(
            "Instrumentation (minifiedSmoke)",
            "Verify minified JSON exports",
            "Require minified export test results",
        ),
        job(
            "Security",
            "Scan full history for secrets",
            "Generate release runtime CycloneDX SBOM",
            "Report release runtime SBOM vulnerabilities",
            "Gate fixed high and critical release runtime vulnerabilities",
        ),
    ]


class FakeGitHub:
    def __init__(self, runs=None, jobs=None):
        self.runs = [successful_run()] if runs is None else runs
        self.jobs = successful_jobs() if jobs is None else jobs
        self.on_jobs = lambda: None

    def __call__(self, path):
        query = parse_qs(urlsplit(path).query)
        if "/workflows/ci.yml/runs?" in path:
            assert query["head_sha"] == [COMMIT]
            assert query["branch"] == ["main"]
            assert query["event"] == ["push"]
            return {"workflow_runs": deepcopy(self.runs)}
        if "/jobs?" in path:
            assert query["filter"] == ["latest"]
            page = int(query["page"][0])
            offset = (page - 1) * 100
            result = {"jobs": deepcopy(self.jobs[offset : offset + 100])}
            self.on_jobs()
            return result
        for run in self.runs:
            if path.endswith(f"/runs/{run['id']}"):
                return deepcopy(run)
        raise AssertionError(f"Unexpected API request: {path}")


class FakeClock:
    def __init__(self):
        self.now = 0
        self.on_sleep = lambda: None

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds
        self.on_sleep()


class ReleaseCiTest(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.enterContext(redirect_stdout(io.StringIO()))

    def plan(self, api, **options):
        return plan_verification(
            api,
            REPOSITORY,
            COMMIT,
            clock=self.clock,
            sleep=self.clock.sleep,
            **options,
        )

    def test_complete_ci_on_the_tagged_commit_needs_no_verification(self):
        result = self.plan(FakeGitHub())

        self.assertEqual(result["required"], "false")
        self.assertEqual(result["instrumentation-suites"], "[]")
        self.assertEqual(
            json.loads(result["skip-jobs"]), ["quality", "build-unit", "security"]
        )

    def test_waits_for_running_ci_instead_of_starting_duplicate_tests(self):
        api = FakeGitHub(runs=[successful_run(status="in_progress", conclusion=None)])
        self.clock.on_sleep = lambda: api.runs[0].update(
            status="completed", conclusion="success"
        )

        result = self.plan(api)

        self.assertEqual(result["required"], "false")
        self.assertEqual(self.clock.now, 30)

    def test_allows_time_for_the_main_push_run_to_appear(self):
        api = FakeGitHub(runs=[])
        self.clock.on_sleep = lambda: api.runs.append(successful_run())

        result = self.plan(api)

        self.assertEqual(result["required"], "false")
        self.assertEqual(self.clock.now, 30)

    def test_missing_run_requests_full_verification_after_discovery_window(self):
        result = self.plan(FakeGitHub(runs=[]))

        self.assertEqual(result["required"], "true")
        self.assertEqual(result["skip-jobs"], "[]")
        self.assertEqual(
            json.loads(result["instrumentation-suites"]), ["debug", "minifiedSmoke"]
        )
        self.assertEqual(self.clock.now, 60)

    def test_wrong_commit_branch_event_workflow_or_repository_cannot_authorize_release(
        self,
    ):
        for changed_field in (
            {"head_sha": "b" * 40},
            {"head_branch": "feature"},
            {"event": "pull_request"},
            {"event": "workflow_dispatch"},
            {"path": ".github/workflows/unrelated.yml"},
            {"repository": {"full_name": "someone/fork"}},
        ):
            with self.subTest(changed_field=changed_field):
                result = self.plan(FakeGitHub(runs=[successful_run(**changed_field)]))
                self.assertEqual(result["required"], "true")
                self.assertEqual(result["skip-jobs"], "[]")

    def test_failed_or_cancelled_ci_blocks_publication(self):
        for conclusion in ("failure", "cancelled", "timed_out", "action_required"):
            with self.subTest(conclusion=conclusion):
                api = FakeGitHub(runs=[successful_run(conclusion=conclusion)])
                with self.assertRaisesRegex(RuntimeError, conclusion):
                    self.plan(api)

    def test_newer_failed_run_cannot_be_hidden_by_an_older_success(self):
        api = FakeGitHub(
            runs=[successful_run(), successful_run(id=43, conclusion="failure")]
        )

        with self.assertRaisesRegex(RuntimeError, "failure"):
            self.plan(api)

    def test_waiting_has_a_deadline_and_does_not_fall_back_to_duplicate_tests(self):
        api = FakeGitHub(runs=[successful_run(status="queued", conclusion=None)])

        with self.assertRaisesRegex(RuntimeError, "Timed out"):
            self.plan(api, wait_seconds=90)

        self.assertEqual(self.clock.now, 90)

    def test_missing_or_skipped_device_suite_runs_only_that_suite(self):
        for missing_suite in ("debug", "minifiedSmoke"):
            for conclusion in ("missing", "skipped"):
                with self.subTest(suite=missing_suite, conclusion=conclusion):
                    jobs = successful_jobs()
                    missing_name = f"Instrumentation ({missing_suite})"
                    if conclusion == "missing":
                        jobs = [
                            entry for entry in jobs if entry["name"] != missing_name
                        ]
                    else:
                        next(entry for entry in jobs if entry["name"] == missing_name)[
                            "conclusion"
                        ] = "skipped"

                    result = self.plan(FakeGitHub(jobs=jobs))

                    self.assertEqual(result["required"], "true")
                    self.assertEqual(
                        json.loads(result["instrumentation-suites"]), [missing_suite]
                    )
                    self.assertEqual(
                        json.loads(result["skip-jobs"]),
                        ["quality", "build-unit", "security"],
                    )

    def test_secret_scan_alone_does_not_count_as_full_security_verification(self):
        jobs = successful_jobs()
        jobs[-1] = job("Security", "Scan full history for secrets")

        result = self.plan(FakeGitHub(jobs=jobs))

        self.assertEqual(result["required"], "true")
        self.assertEqual(json.loads(result["skip-jobs"]), ["quality", "build-unit"])
        self.assertEqual(result["instrumentation-suites"], "[]")

    def test_a_skipped_export_result_guard_requires_the_smoke_suite(self):
        jobs = successful_jobs()
        jobs[3]["steps"][-1]["conclusion"] = "skipped"

        result = self.plan(FakeGitHub(jobs=jobs))

        self.assertEqual(
            json.loads(result["instrumentation-suites"]), ["minifiedSmoke"]
        )

    def test_aggregate_success_does_not_hide_an_individual_failed_job(self):
        jobs = successful_jobs()
        jobs[0]["conclusion"] = "failure"

        with self.assertRaisesRegex(RuntimeError, "Quality.*failure"):
            self.plan(FakeGitHub(jobs=jobs))

    def test_failed_required_step_blocks_even_when_the_job_reports_success(self):
        jobs = successful_jobs()
        jobs[-1]["steps"][-1]["conclusion"] = "failure"

        with self.assertRaisesRegex(RuntimeError, "Required CI step.*failure"):
            self.plan(FakeGitHub(jobs=jobs))

    def test_jobs_on_later_api_pages_are_reused(self):
        jobs = [job(f"Extra job {number}") for number in range(100)] + successful_jobs()

        result = self.plan(FakeGitHub(jobs=jobs))

        self.assertEqual(result["required"], "false")

    def test_old_attempt_results_are_not_used_when_ci_is_rerun_during_inspection(self):
        api = FakeGitHub()
        api.on_jobs = lambda: api.runs[0].update(
            run_attempt=2, status="in_progress", conclusion=None
        )
        self.clock.on_sleep = lambda: api.runs[0].update(
            status="completed", conclusion="failure"
        )

        with self.assertRaisesRegex(RuntimeError, "failure"):
            self.plan(api)

    def test_api_errors_block_release_instead_of_being_treated_as_missing_ci(self):
        def denied(_path):
            raise URLError("API unavailable")

        with self.assertRaises(URLError):
            self.plan(denied)

    def test_workflow_entrypoint_writes_the_reuse_decision_as_job_outputs(self):
        api = FakeGitHub()

        def response(request, timeout):
            self.assertEqual(request.get_method(), "GET")
            self.assertEqual(timeout, 30)
            self.assertEqual(request.host, "api.github.com")
            path = request.full_url.removeprefix("https://api.github.com/")
            return io.BytesIO(json.dumps(api(path)).encode())

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "outputs"
            environment = {
                "GITHUB_API_URL": "https://api.github.com",
                "GITHUB_REPOSITORY": REPOSITORY,
                "GITHUB_SHA": COMMIT,
                "GH_TOKEN": "test-token",
                "GITHUB_OUTPUT": str(output),
            }
            with (
                patch.dict(os.environ, environment),
                patch("release_ci.urlopen", response),
            ):
                main()

            values = dict(
                line.split("=", 1) for line in output.read_text().splitlines()
            )
            self.assertEqual(values["required"], "false")
            self.assertEqual(values["instrumentation-suites"], "[]")

    def test_api_error_writes_no_outputs_that_could_allow_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "outputs"
            environment = {
                "GITHUB_API_URL": "https://api.github.com",
                "GITHUB_REPOSITORY": REPOSITORY,
                "GITHUB_SHA": COMMIT,
                "GH_TOKEN": "test-token",
                "GITHUB_OUTPUT": str(output),
            }
            error = HTTPError("https://api.github.com", 403, "Forbidden", {}, None)
            self.addCleanup(error.close)
            with (
                patch.dict(os.environ, environment),
                patch("release_ci.urlopen", side_effect=error),
                self.assertRaises(HTTPError),
            ):
                main()

            self.assertFalse(output.exists())

    def ci_gate(self, **results):
        workflow = (Path(__file__).parent.parent / "workflows" / "ci.yml").read_text()
        gate = workflow.split("\n  ci-gate:\n", 1)[1].split("\n  legacy-gate:\n", 1)[0]
        script = textwrap.dedent(gate.split("        run: |\n", 1)[1])
        environment = {
            "CHANGES_RESULT": "success",
            "QUALITY_RESULT": "success",
            "BUILD_UNIT_RESULT": "success",
            "INSTRUMENTATION_RESULT": "success",
            "SECURITY_RESULT": "success",
            "SECURITY_REUSED": "false",
            **results,
        }
        return subprocess.run(
            ["bash", "-e", "-c", script],
            check=False,
            env=environment,
            capture_output=True,
            text=True,
        ).returncode

    def test_ci_gate_requires_evidence_before_accepting_skipped_security(self):
        self.assertNotEqual(self.ci_gate(SECURITY_RESULT="skipped"), 0)
        self.assertEqual(
            self.ci_gate(SECURITY_RESULT="skipped", SECURITY_REUSED="true"), 0
        )

    def test_ci_gate_blocks_failure_in_each_missing_verification_job(self):
        for key in (
            "CHANGES_RESULT",
            "QUALITY_RESULT",
            "BUILD_UNIT_RESULT",
            "INSTRUMENTATION_RESULT",
            "SECURITY_RESULT",
        ):
            with self.subTest(job=key):
                self.assertNotEqual(self.ci_gate(**{key: "failure"}), 0)


if __name__ == "__main__":
    unittest.main()
