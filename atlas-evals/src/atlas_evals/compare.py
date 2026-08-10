"""Compare two results files: aggregate deltas, per-question regressions, and a gating verdict.

Pure functions produce a :class:`ComparisonReport`; the CLI renders it and maps
:meth:`ComparisonReport.has_gated_regression` to the exit code. A metric "regresses" when it moves
in the worse direction by more than ``tolerance`` (default 0.02). Latency is reported but never
gated (it is environment-noisy).
"""

from __future__ import annotations

from dataclasses import dataclass

from atlas_evals.metrics import ChatSummary, EngineSummary
from atlas_evals.results import QuestionResult, RunResults

DEFAULT_TOLERANCE = 0.02

# (label, attribute, higher_is_better, gated)
_ENGINE_METRICS = [
    ("doc_hit", "document_hit_rate", True, True),
    ("page_hit", "page_hit_rate", True, True),
    ("mrr", "mrr", True, True),
    ("mean_ms", "avg_latency_ms", False, False),
]
_CHAT_METRICS = [
    ("citation_document_accuracy", "citation_document_accuracy", True, True),
    ("citation_page_hit_rate", "citation_page_hit_rate", True, True),
    ("false_abstention_rate", "false_abstention_rate", False, True),
    ("abstention_rate", "abstention_rate", True, True),
    ("mean_latency_ms", "avg_latency_ms", False, False),
]


@dataclass
class MetricDelta:
    label: str
    baseline: float | None
    candidate: float | None
    delta: float | None
    higher_is_better: bool
    gated: bool
    regressed: bool


def _make_delta(
    label: str,
    baseline: float | None,
    candidate: float | None,
    higher_is_better: bool,
    gated: bool,
    tolerance: float,
) -> MetricDelta:
    if baseline is None or candidate is None:
        return MetricDelta(label, baseline, candidate, None, higher_is_better, gated, False)
    delta = candidate - baseline
    if higher_is_better:
        regressed = gated and delta < -tolerance
    else:
        regressed = gated and delta > tolerance
    return MetricDelta(label, baseline, candidate, delta, higher_is_better, gated, regressed)


def _summary_deltas(
    baseline: EngineSummary | ChatSummary,
    candidate: EngineSummary | ChatSummary,
    specs: list[tuple[str, str, bool, bool]],
    tolerance: float,
) -> list[MetricDelta]:
    return [
        _make_delta(
            label,
            getattr(baseline, attr),
            getattr(candidate, attr),
            higher,
            gated,
            tolerance,
        )
        for label, attr, higher, gated in specs
    ]


@dataclass
class ComparisonReport:
    baseline_dataset: str
    candidate_dataset: str
    schema_ok: bool
    warnings: list[str]
    engine_deltas: dict[str, list[MetricDelta]]
    chat_deltas: list[MetricDelta]
    question_regressions: list[str]
    tolerance: float = DEFAULT_TOLERANCE

    def has_gated_regression(self) -> bool:
        engine_reg = any(d.regressed for deltas in self.engine_deltas.values() for d in deltas)
        chat_reg = any(d.regressed for d in self.chat_deltas)
        return engine_reg or chat_reg


def _dataset_warnings(baseline: RunResults, candidate: RunResults) -> list[str]:
    warnings: list[str] = []
    if baseline.dataset != candidate.dataset:
        warnings.append(
            f"Dataset name differs: baseline '{baseline.dataset}' vs candidate "
            f"'{candidate.dataset}' — the comparison may not be meaningful."
        )
    baseline_ids = {q.id for q in baseline.questions}
    candidate_ids = {q.id for q in candidate.questions}
    if baseline_ids != candidate_ids:
        only_baseline = sorted(baseline_ids - candidate_ids)
        only_candidate = sorted(candidate_ids - baseline_ids)
        warnings.append(
            "Question id sets differ — "
            f"only in baseline: {only_baseline or '[]'}; "
            f"only in candidate: {only_candidate or '[]'}."
        )
    return warnings


def _question_regressions(baseline: RunResults, candidate: RunResults) -> list[str]:
    base_by_id = {q.id: q for q in baseline.questions}
    regressions: list[str] = []
    for candidate_q in candidate.questions:
        baseline_q = base_by_id.get(candidate_q.id)
        if baseline_q is None:
            continue
        regressions.extend(_question_regression_lines(baseline_q, candidate_q))
    return regressions


def _question_regression_lines(
    baseline_q: QuestionResult, candidate_q: QuestionResult
) -> list[str]:
    lines: list[str] = []
    for engine, base_engine in baseline_q.retrieval.items():
        cand_engine = candidate_q.retrieval.get(engine)
        if cand_engine is None:
            continue
        if base_engine.metrics.document_hit and not cand_engine.metrics.document_hit:
            lines.append(f"{candidate_q.id} · {engine}: document hit → miss")
        if base_engine.metrics.page_hit and not cand_engine.metrics.page_hit:
            lines.append(f"{candidate_q.id} · {engine}: page hit → miss")

    if baseline_q.chat is not None and candidate_q.chat is not None:
        base_m = baseline_q.chat.metrics
        cand_m = candidate_q.chat.metrics
        if base_m.citation_documents_ok and not cand_m.citation_documents_ok:
            lines.append(f"{candidate_q.id} · chat: citation-documents ok → fail")
        if base_m.citation_page_hit and not cand_m.citation_page_hit:
            lines.append(f"{candidate_q.id} · chat: citation page hit → miss")
        if not base_m.false_abstention and cand_m.false_abstention:
            lines.append(f"{candidate_q.id} · chat: newly false-abstained")
        if base_m.correct_abstention and not cand_m.correct_abstention:
            lines.append(f"{candidate_q.id} · chat: stopped abstaining on unanswerable (leak)")
    return lines


def compare_runs(
    baseline: RunResults, candidate: RunResults, tolerance: float = DEFAULT_TOLERANCE
) -> ComparisonReport:
    schema_ok = baseline.schema_version == candidate.schema_version
    warnings = _dataset_warnings(baseline, candidate)

    engine_deltas: dict[str, list[MetricDelta]] = {}
    for engine, base_summary in baseline.summary.engines.items():
        cand_summary = candidate.summary.engines.get(engine)
        if cand_summary is None:
            warnings.append(f"Engine '{engine}' is missing from the candidate run.")
            continue
        engine_deltas[engine] = _summary_deltas(
            base_summary, cand_summary, _ENGINE_METRICS, tolerance
        )

    chat_deltas: list[MetricDelta] = []
    if baseline.summary.chat is not None and candidate.summary.chat is not None:
        chat_deltas = _summary_deltas(
            baseline.summary.chat, candidate.summary.chat, _CHAT_METRICS, tolerance
        )

    return ComparisonReport(
        baseline_dataset=baseline.dataset,
        candidate_dataset=candidate.dataset,
        schema_ok=schema_ok,
        warnings=warnings,
        engine_deltas=engine_deltas,
        chat_deltas=chat_deltas,
        question_regressions=_question_regressions(baseline, candidate),
        tolerance=tolerance,
    )


# --- rendering -----------------------------------------------------------------------------------


def _fmt(value: float | None, label: str) -> str:
    if value is None:
        return "—"
    return f"{value:.0f}" if "ms" in label else f"{value:.3f}"


def _fmt_delta(delta: MetricDelta) -> str:
    if delta.delta is None:
        return "—"
    sign = "+" if delta.delta >= 0 else "−"
    magnitude = abs(delta.delta)
    body = f"{sign}{magnitude:.0f}" if "ms" in delta.label else f"{sign}{magnitude:.3f}"
    if delta.regressed:
        return f"**⚠ {body} ↓**"
    return body


def _delta_table(title: str, deltas: list[MetricDelta]) -> str:
    lines = [f"| {title} | baseline | candidate | Δ |", "|---|---|---|---|"]
    for d in deltas:
        lines.append(
            f"| {d.label} | {_fmt(d.baseline, d.label)} | "
            f"{_fmt(d.candidate, d.label)} | {_fmt_delta(d)} |"
        )
    return "\n".join(lines)


def render_markdown(report: ComparisonReport) -> str:
    parts: list[str] = ["# Atlas evaluation comparison"]
    verdict = "REGRESSED" if report.has_gated_regression() else "clean"
    parts.append(
        f"Baseline `{report.baseline_dataset}` → candidate `{report.candidate_dataset}` "
        f"(tolerance ±{report.tolerance:.2f}). Gating verdict: **{verdict}**."
    )
    if not report.schema_ok:
        parts.append("> **schema_version mismatch — the runs are not comparable.**")
    for warning in report.warnings:
        parts.append(f"> ⚠ {warning}")

    for engine, deltas in report.engine_deltas.items():
        parts.append(f"## Engine: {engine}")
        parts.append(_delta_table("metric", deltas))
    if report.chat_deltas:
        parts.append("## Chat")
        parts.append(_delta_table("metric", report.chat_deltas))

    parts.append("## Per-question regressions")
    if report.question_regressions:
        parts.append("\n".join(f"- {line}" for line in report.question_regressions))
    else:
        parts.append("None.")

    parts.append(
        "Exit contract: 0 when clean; 1 when any gated aggregate metric regressed beyond "
        f"±{report.tolerance:.2f}. Latency is shown but not gated."
    )
    return "\n\n".join(parts) + "\n"
