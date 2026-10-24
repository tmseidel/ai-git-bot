"""Check provisional review migration filename ordering, not SQL compatibility."""

import argparse
from pathlib import Path
import sys


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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (default: parent of this script's directory)",
    )
    args = parser.parse_args()
    print(
        "Filename ordering prerequisites only; "
        "this does not prove SQL or application compatibility."
    )
    try:
        # Enumerate roots first: rglob alone silently skips missing directories.
        inventory = {
            dialect: {
                path.name
                for path in (args.root / MIGRATIONS / dialect).iterdir()
                if path.is_file()
            }
            for dialect in ("h2", "postgresql")
        }
        for dialect, names in inventory.items():
            names.update(
                path.name
                for path in (args.root / MIGRATIONS / dialect).rglob("*")
                if path.is_file()
            )
    except OSError as error:
        print(f"ERROR: cannot inspect migration directories: {error}", file=sys.stderr)
        return 1

    present = tuple(
        name
        for name in REVIEW_MIGRATIONS
        if any(name in names for names in inventory.values())
    )
    if not present:
        print("OK: no provisional review migrations; prerequisite guard dormant.")
        return 0

    required_reviews = REVIEW_MIGRATIONS[:REVIEW_MIGRATIONS.index(present[-1]) + 1]
    missing = [
        (MIGRATIONS / dialect / name).as_posix()
        for dialect, names in inventory.items()
        for name in PREREQUISITES + required_reviews
        if name not in names
    ]
    if missing:
        print(
            "ERROR: missing review migration prerequisites or dialect counterparts:",
            file=sys.stderr,
        )
        for path in missing:
            print(f"  {path}", file=sys.stderr)
        return 1

    print("OK: review migration filename ordering prerequisites present.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
