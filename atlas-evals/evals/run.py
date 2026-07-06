"""Entry point for the atlas-evals harness.

Usage:
    python -m evals.run --target http://localhost:8080
"""

import argparse


def main() -> None:
    parser = argparse.ArgumentParser(description="Run Atlas RAG evals against a target instance.")
    parser.add_argument("--target", required=True, help="Base URL of the atlas-core instance to evaluate.")
    args = parser.parse_args()

    # TODO(v1): load datasets/, run retrieval + generation evals against args.target,
    # write results to results/.
    raise NotImplementedError("Eval suite not yet implemented.")


if __name__ == "__main__":
    main()
