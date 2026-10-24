from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check_review_migration_prerequisites.py"
MIGRATIONS = Path("src/main/resources/db/migration")
PREREQUISITES = (
    "V59__bot_username_on_git_integration.sql",
    "V60__git_integration_git_author.sql",
)
REVIEW_MIGRATIONS = (
    "V61__agent_review_control.sql",
    "V62__required_workflow_completion_event_keys.sql",
    "V63__review_mcp_read_only_approval.sql",
    "V64__agent_review_admission_snapshot.sql",
    "V65__review_publication_lanes.sql",
)


class ReviewMigrationPrerequisitesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(
            prefix="review migrations ", dir=Path(__file__).resolve().parent
        )
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cwd = self.root / "unrelated cwd"
        self.cwd.mkdir()
        for dialect in ("h2", "postgresql"):
            directory = self.root / MIGRATIONS / dialect
            directory.mkdir(parents=True)
            (directory / "V58__git_integration_token_length.sql").touch()

    def run_check(self):
        return subprocess.run(
            [sys.executable, "-B", str(SCRIPT), "--root", str(self.root)],
            cwd=self.cwd,
            capture_output=True,
            text=True,
            check=False,
        )

    def add_migrations(self, names):
        for dialect in ("h2", "postgresql"):
            for name in names:
                (self.root / MIGRATIONS / dialect / name).touch()

    def test_v58_base_without_review_migrations_passes_dormantly(self):
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("dormant", result.stdout)
        self.assertIn("does not prove SQL or application compatibility", result.stdout)

    def test_review_migration_in_either_dialect_requires_prerequisite_pairs(self):
        for dialect in ("h2", "postgresql"):
            for migration in REVIEW_MIGRATIONS:
                with self.subTest(dialect=dialect, migration=migration):
                    path = self.root / MIGRATIONS / dialect / migration
                    path.touch()
                    result = self.run_check()
                    path.unlink()
                    self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                    for required_dialect in ("h2", "postgresql"):
                        for prerequisite in PREREQUISITES:
                            self.assertIn(
                                f"{MIGRATIONS.as_posix()}/{required_dialect}/{prerequisite}",
                                result.stderr,
                            )

    def test_each_review_migration_requires_its_other_dialect_counterpart(self):
        self.add_migrations(PREREQUISITES)
        for dialect, counterpart in (("h2", "postgresql"), ("postgresql", "h2")):
            for migration in REVIEW_MIGRATIONS:
                with self.subTest(dialect=dialect, migration=migration):
                    path = self.root / MIGRATIONS / dialect / migration
                    path.touch()
                    result = self.run_check()
                    path.unlink()
                    self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                    self.assertIn(
                        f"{MIGRATIONS.as_posix()}/{counterpart}/{migration}", result.stderr
                    )

    def test_nested_review_migrations_cannot_bypass_prerequisites(self):
        for dialect in ("h2", "postgresql"):
            directory = self.root / MIGRATIONS / dialect / "review"
            directory.mkdir()
            for migration in REVIEW_MIGRATIONS:
                with self.subTest(dialect=dialect, migration=migration):
                    path = directory / migration
                    path.touch()
                    result = self.run_check()
                    path.unlink()
                    self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                    self.assertNotIn("dormant", result.stdout)
                    for required_dialect in ("h2", "postgresql"):
                        for prerequisite in PREREQUISITES:
                            self.assertIn(
                                f"{MIGRATIONS.as_posix()}/{required_dialect}/{prerequisite}",
                                result.stderr,
                            )

    def test_complete_provisional_chain_passes_without_claiming_compatibility(self):
        self.add_migrations(PREREQUISITES + REVIEW_MIGRATIONS)
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("dormant", result.stdout)
        self.assertIn("does not prove SQL or application compatibility", result.stdout)

    def test_later_assigned_migrations_require_all_earlier_assigned_pairs(self):
        self.add_migrations(PREREQUISITES)
        for migration, predecessors in (
            (
                "V62__required_workflow_completion_event_keys.sql",
                ("V61__agent_review_control.sql",),
            ),
            (
                "V63__review_mcp_read_only_approval.sql",
                (
                    "V61__agent_review_control.sql",
                    "V62__required_workflow_completion_event_keys.sql",
                ),
            ),
            (
                "V64__agent_review_admission_snapshot.sql",
                (
                    "V61__agent_review_control.sql",
                    "V62__required_workflow_completion_event_keys.sql",
                    "V63__review_mcp_read_only_approval.sql",
                ),
            ),
            (
                "V65__review_publication_lanes.sql",
                (
                    "V61__agent_review_control.sql",
                    "V62__required_workflow_completion_event_keys.sql",
                    "V63__review_mcp_read_only_approval.sql",
                    "V64__agent_review_admission_snapshot.sql",
                ),
            ),
        ):
            with self.subTest(migration=migration):
                self.add_migrations((migration,))
                result = self.run_check()
                for dialect in ("h2", "postgresql"):
                    (self.root / MIGRATIONS / dialect / migration).unlink()
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                for dialect in ("h2", "postgresql"):
                    for predecessor in predecessors:
                        self.assertIn(
                            f"{MIGRATIONS.as_posix()}/{dialect}/{predecessor}", result.stderr
                        )

    def test_each_complete_assigned_prefix_passes_without_later_migrations(self):
        self.add_migrations(PREREQUISITES)
        for migration in REVIEW_MIGRATIONS:
            with self.subTest(highest=migration):
                self.add_migrations((migration,))
                result = self.run_check()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertNotIn("dormant", result.stdout)

    def test_v64_requires_v63_then_accepts_complete_prefix(self):
        self.add_migrations(PREREQUISITES + REVIEW_MIGRATIONS[:4])
        predecessor = "V63__review_mcp_read_only_approval.sql"
        for dialect in ("h2", "postgresql"):
            (self.root / MIGRATIONS / dialect / predecessor).unlink()
        result = self.run_check()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        for dialect in ("h2", "postgresql"):
            self.assertIn(f"{MIGRATIONS.as_posix()}/{dialect}/{predecessor}", result.stderr)

        self.add_migrations((predecessor,))
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("dormant", result.stdout)

    def test_unassigned_filenames_do_not_activate_guard(self):
        self.add_migrations(("V61__unrelated.sql", "V63__unrelated.sql", "V99__unrelated.sql"))
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("dormant", result.stdout)

    def test_missing_any_chain_file_in_either_dialect_fails(self):
        self.add_migrations(PREREQUISITES + REVIEW_MIGRATIONS)
        for dialect in ("h2", "postgresql"):
            for name in PREREQUISITES + REVIEW_MIGRATIONS:
                with self.subTest(dialect=dialect, name=name):
                    path = self.root / MIGRATIONS / dialect / name
                    path.unlink()
                    result = self.run_check()
                    path.touch()
                    self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                    self.assertIn(f"{MIGRATIONS.as_posix()}/{dialect}/{name}", result.stderr)

    def test_prerequisites_require_exact_filenames_and_regular_files(self):
        self.add_migrations(PREREQUISITES + REVIEW_MIGRATIONS)
        directory = self.root / MIGRATIONS / "postgresql"
        expected = directory / "V60__git_integration_git_author.sql"
        for wrong_name in ("V60__other.sql", "v60__git_integration_git_author.sql"):
            with self.subTest(wrong_name=wrong_name):
                replacement = directory / wrong_name
                expected.rename(replacement)
                result = self.run_check()
                replacement.rename(expected)
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn(expected.name, result.stderr)
        expected.unlink()
        expected.mkdir()
        result = self.run_check()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn(expected.name, result.stderr)

    def test_missing_dialect_directory_fails_closed_with_diagnostic(self):
        for dialect in ("h2", "postgresql"):
            with self.subTest(dialect=dialect):
                directory = self.root / MIGRATIONS / dialect
                moved = self.root / dialect
                directory.rename(moved)
                result = self.run_check()
                moved.rename(directory)
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn("ERROR: cannot inspect migration directories", result.stderr)
                self.assertNotIn("Traceback", result.stderr)

    def test_default_root_is_relative_to_script_not_working_directory(self):
        self.add_migrations(PREREQUISITES + REVIEW_MIGRATIONS)
        copied_script = self.root / "scripts" / SCRIPT.name
        copied_script.parent.mkdir()
        shutil.copyfile(SCRIPT, copied_script)
        result = subprocess.run(
            [sys.executable, "-B", str(copied_script)],
            cwd=self.cwd,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("dormant", result.stdout)


if __name__ == "__main__":
    unittest.main()
