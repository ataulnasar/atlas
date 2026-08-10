"""Render a results file (:class:`~atlas_evals.results.RunResults`) as Markdown or HTML.

Pure: functions here take a parsed ``RunResults`` and return a string. The CLI does the file I/O.
The content is built once as a list of :class:`Section` blocks, then emitted by either renderer.
"""

from __future__ import annotations

import html
from dataclasses import dataclass, field

from atlas_evals.results import QuestionResult, RunResults

# gpt-5-mini published standard rates (USD per 1M tokens): input, output. Static, like atlas-core's
# atlas.generation.cost — not fetched. Update if the model or its pricing changes.
PRICES_PER_MILLION: dict[str, tuple[float, float]] = {"gpt-5-mini": (0.25, 2.00)}

CATEGORY_ORDER = ["keyword", "semantic", "cross-document", "hard", "unanswerable", "multi-turn"]
RETRIEVAL_ENGINES = ["vector", "keyword", "hybrid"]

CROSS_DOCUMENT_NOTE = (
    "Cross-document entries score **pages against the primary (first-listed) document only**; "
    "secondary documents are credited at the document level. The citation-document metric is a "
    "**strict subset check** (all cited docs must be expected), so it can penalize a legitimate "
    "secondary or neighbouring citation even when the answer is well grounded — see the "
    "failure-evidence section to adjudicate each case. (Dataset note: `cross_document_scoring`.)"
)


@dataclass
class Table:
    headers: list[str]
    rows: list[list[str]]


@dataclass
class Section:
    title: str
    level: int
    blocks: list[str | Table] = field(default_factory=list)


def _pct(value: float) -> str:
    return f"{value:.2f}"


def _mrr(value: float) -> str:
    return f"{value:.3f}"


def _ms(value: float | None) -> str:
    return "—" if value is None else f"{value:.0f}"


def _rate(flags: list[bool]) -> str:
    return "—" if not flags else f"{sum(flags) / len(flags):.2f}"


def _mark(value: bool) -> str:
    return "✓" if value else "·"


def _estimate_cost(results: RunResults) -> tuple[int, int, int, str | None, float | None]:
    prompt = completion = total = 0
    model: str | None = None
    for q in results.questions:
        if q.chat is None or q.chat.usage is None:
            continue
        usage = q.chat.usage
        prompt += usage.prompt_tokens or 0
        completion += usage.completion_tokens or 0
        total += usage.total_tokens or 0
        if model is None:
            model = usage.model
    cost: float | None = None
    if model is not None:
        for prefix, (in_price, out_price) in PRICES_PER_MILLION.items():
            if model.startswith(prefix):
                cost = prompt / 1_000_000 * in_price + completion / 1_000_000 * out_price
                break
    return prompt, completion, total, model, cost


def _header_section(results: RunResults) -> Section:
    s = results.summary
    table = Table(
        headers=["Field", "Value"],
        rows=[
            ["Dataset", results.dataset],
            ["Base URL", results.base_url],
            ["Engines", ", ".join(results.engines)],
            ["top_k", str(results.top_k)],
            ["Started", results.started_at.isoformat()],
            ["Finished", results.finished_at.isoformat()],
            ["Questions", str(s.total_questions)],
            ["Errored", f"{s.errored_questions} ({s.error_rate:.0%})"],
            ["Schema version", str(results.schema_version)],
        ],
    )
    return Section("Atlas evaluation report", 1, [table])


def _retrieval_section(results: RunResults) -> Section:
    rows = []
    for engine in RETRIEVAL_ENGINES:
        summary = results.summary.engines.get(engine)
        if summary is None:
            continue
        rows.append(
            [
                engine,
                _pct(summary.document_hit_rate),
                _pct(summary.page_hit_rate),
                _mrr(summary.mrr),
                str(summary.evaluated),
                _ms(summary.avg_latency_ms),
            ]
        )
    table = Table(["Engine", "doc_hit", "page_hit", "MRR", "n", "mean_ms"], rows)
    return Section("Retrieval summary", 2, [table])


def _chat_section(results: RunResults) -> Section:
    chat = results.summary.chat
    if chat is None:
        return Section("Chat summary", 2, ["Chat was not run."])
    prompt, completion, total, model, cost = _estimate_cost(results)
    cost_text = f"${cost:.4f}" if cost is not None else "n/a (unknown model pricing)"
    table = Table(
        ["Metric", "Value", "Denominator"],
        rows=[
            [
                "citation_document_accuracy",
                _pct(chat.citation_document_accuracy),
                "answerable questions with ≥1 citation",
            ],
            [
                "citation_page_hit_rate",
                _pct(chat.citation_page_hit_rate),
                "all answerable questions",
            ],
            [
                "false_abstention_rate",
                _pct(chat.false_abstention_rate),
                "all answerable questions (lower is better)",
            ],
            [
                "abstention_rate",
                _pct(chat.abstention_rate),
                "unanswerable questions (higher is better)",
            ],
            ["mean latency (ms)", _ms(chat.avg_latency_ms), "evaluated chat turns"],
            ["total tokens", f"{total:,}", "final chat turns (setup turns not counted)"],
            ["estimated cost", cost_text, f"model {model or '—'} @ static rates"],
        ],
    )
    return Section("Chat summary", 2, [table])


def _category_section(results: RunResults) -> Section:
    by_category: dict[str, list[QuestionResult]] = {c: [] for c in CATEGORY_ORDER}
    for q in results.questions:
        by_category.setdefault(q.category, []).append(q)

    rows = []
    for category in CATEGORY_ORDER:
        questions = by_category.get(category, [])
        if not questions:
            continue
        answerable = [q for q in questions if q.chat is None or q.chat.metrics.answerable]
        page_hits = {
            engine: _rate(
                [
                    q.retrieval[engine].metrics.page_hit
                    for q in questions
                    if engine in q.retrieval
                ]
            )
            for engine in RETRIEVAL_ENGINES
        }
        with_citations = [
            q for q in answerable if q.chat is not None and q.chat.metrics.num_citations > 0
        ]
        cite_doc = _rate(
            [bool(q.chat and q.chat.metrics.citation_documents_ok) for q in with_citations]
        )
        cite_page = _rate(
            [bool(q.chat and q.chat.metrics.citation_page_hit) for q in answerable]
        )
        # abstention: correct for unanswerable, false for answerable — report the relevant one.
        if category == "unanswerable":
            abstain = _rate([bool(q.chat and q.chat.metrics.correct_abstention) for q in questions])
        else:
            abstain = _rate([bool(q.chat and q.chat.metrics.false_abstention) for q in answerable])
        rows.append(
            [
                category,
                str(len(questions)),
                page_hits["vector"],
                page_hits["keyword"],
                page_hits["hybrid"],
                cite_doc,
                cite_page,
                abstain,
            ]
        )
    table = Table(
        [
            "category", "n", "vec_page", "key_page", "hyb_page",
            "chat_cite_doc", "chat_cite_page", "abstain*",
        ],
        rows,
    )
    return Section(
        "Per-category breakdown",
        2,
        [
            table,
            "Retrieval columns are page_hit rates (blank where an engine did not run, e.g. "
            "unanswerables). `abstain*` is the false-abstention rate for answerable categories and "
            "the correct-abstention rate for `unanswerable`.",
        ],
    )


def _detail_section(results: RunResults) -> Section:
    rows = []
    for q in results.questions:
        marks = []
        for engine in RETRIEVAL_ENGINES:
            er = q.retrieval.get(engine)
            marks.append(
                f"{_mark(er.metrics.document_hit)}{_mark(er.metrics.page_hit)}" if er else "—"
            )
        chat_cites = str(q.chat.metrics.num_citations) if q.chat else "—"
        flags = ", ".join(_flags(q)) or "—"
        rows.append([q.id, q.category, marks[0], marks[1], marks[2], chat_cites, flags])
    table = Table(
        ["id", "category", "vec", "key", "hyb", "chat_cites", "flags"], rows
    )
    return Section(
        "Per-question detail",
        2,
        [table, "Retrieval marks are `doc``page` (`✓`/`·`); `—` where the engine did not run."],
    )


def _flags(q: QuestionResult) -> list[str]:
    flags: list[str] = []
    if q.errored:
        flags.append(f"errors={len(q.errors)}")
    if q.chat is not None:
        m = q.chat.metrics
        if m.false_abstention:
            flags.append("false-abstain")
        if m.answerable and m.num_citations > 0 and not m.citation_documents_ok:
            flags.append("cite-doc✗")
        if m.correct_abstention is False:  # unanswerable that leaked citations
            flags.append("leak")
    return flags


def _failure_evidence_section(results: RunResults) -> Section:
    rows = []
    for q in results.questions:
        if q.chat is None:
            continue
        m = q.chat.metrics
        if not (m.answerable and m.num_citations > 0 and not m.citation_documents_ok):
            continue
        cited = sorted({c.document_filename for c in q.chat.citations})
        expected = set(q.expected_documents)
        unexpected = [d for d in cited if d not in expected]
        rows.append(
            [
                q.id,
                ", ".join(q.expected_documents),
                ", ".join(cited),
                ", ".join(unexpected),
            ]
        )
    blocks: list[str | Table] = [CROSS_DOCUMENT_NOTE]
    if rows:
        blocks.append(Table(["id", "expected", "cited", "unexpected (cited∉expected)"], rows))
    else:
        blocks.append("No answerable question failed the citation-document subset check.")
    return Section("Failure evidence: citation-document misses", 2, blocks)


def _denominators_section(results: RunResults) -> Section:
    s = results.summary
    any_engine = next(iter(s.engines.values()), None)
    retrieval_n = any_engine.evaluated if any_engine is not None else 0
    chat_n = s.chat.evaluated if s.chat is not None else 0
    text = (
        f"- **Retrieval n = {retrieval_n}** (of {s.total_questions}): the {s.unanswerable} "
        "unanswerable questions skip retrieval (no expected document), so retrieval is scored "
        "over the answerable set only.\n"
        "- **Multi-turn** questions count as answerable and DO run retrieval, but on the final "
        "question text alone — the retrieval endpoints are stateless (no conversation history), "
        "which can understate multi-turn retrieval. Their chat turn replays the setup turns on "
        "one conversation before the evaluated question.\n"
        f"- **Chat evaluated = {chat_n}** (all questions): `citation_document_accuracy` is over "
        "answerable questions with ≥1 citation; `citation_page_hit_rate` and "
        "`false_abstention_rate` are over all answerable questions; `abstention_rate` is over "
        "unanswerable questions."
    )
    notes = "\n".join(f"- _{n}_" for n in s.notes)
    return Section("Denominators & notes", 2, [text, notes])


def _sections(results: RunResults) -> list[Section]:
    return [
        _header_section(results),
        _retrieval_section(results),
        _chat_section(results),
        _category_section(results),
        _failure_evidence_section(results),
        _detail_section(results),
        _denominators_section(results),
    ]


# --- markdown ------------------------------------------------------------------------------------


def _md_table(table: Table) -> str:
    lines = [
        "| " + " | ".join(table.headers) + " |",
        "|" + "|".join(["---"] * len(table.headers)) + "|",
    ]
    lines.extend("| " + " | ".join(row) + " |" for row in table.rows)
    return "\n".join(lines)


def render_markdown(results: RunResults) -> str:
    parts: list[str] = []
    for section in _sections(results):
        parts.append(f"{'#' * section.level} {section.title}")
        for block in section.blocks:
            parts.append(_md_table(block) if isinstance(block, Table) else block)
    return "\n\n".join(parts) + "\n"


# --- html ----------------------------------------------------------------------------------------

_HTML_STYLE = """
body { font: 15px/1.6 -apple-system, Segoe UI, Roboto, sans-serif; max-width: 60rem;
       margin: 2rem auto; padding: 0 1rem; color: #1a1c22; }
h1 { font-size: 1.7rem; } h2 { font-size: 1.2rem; margin-top: 2rem;
     border-bottom: 1px solid #e2e5ea; padding-bottom: .3rem; }
table { border-collapse: collapse; width: 100%; margin: 1rem 0; font-size: 14px; }
th, td { border: 1px solid #e2e5ea; padding: 6px 10px; text-align: left; }
th { background: #f1f3f6; } td { font-variant-numeric: tabular-nums; }
code { background: #f1f3f6; padding: 1px 4px; border-radius: 3px; }
""".strip()


def _html_table(table: Table) -> str:
    head = "".join(f"<th>{html.escape(h)}</th>" for h in table.headers)
    body = "".join(
        "<tr>" + "".join(f"<td>{html.escape(cell)}</td>" for cell in row) + "</tr>"
        for row in table.rows
    )
    return f"<table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table>"


def _html_paragraph(text: str) -> str:
    # Minimal inline formatting: escape, then render `code` and **bold**.
    escaped = html.escape(text)
    escaped = _replace_pairs(escaped, "**", "<strong>", "</strong>")
    escaped = _replace_pairs(escaped, "`", "<code>", "</code>")
    if escaped.lstrip().startswith("- "):
        items = "".join(
            f"<li>{line.strip()[2:]}</li>" for line in escaped.splitlines() if line.strip()
        )
        return f"<ul>{items}</ul>"
    return f"<p>{escaped.replace(chr(10), '<br>')}</p>"


def _replace_pairs(text: str, marker: str, open_tag: str, close_tag: str) -> str:
    parts = text.split(marker)
    out = parts[0]
    for i, part in enumerate(parts[1:], start=1):
        out += (open_tag if i % 2 == 1 else close_tag) + part
    return out


def render_html(results: RunResults) -> str:
    parts: list[str] = []
    for section in _sections(results):
        parts.append(f"<h{section.level}>{html.escape(section.title)}</h{section.level}>")
        for block in section.blocks:
            parts.append(_html_table(block) if isinstance(block, Table) else _html_paragraph(block))
    body = "\n".join(parts)
    return (
        "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
        f"<title>Atlas evaluation report — {html.escape(results.dataset)}</title>"
        f"<style>{_HTML_STYLE}</style></head><body>\n{body}\n</body></html>\n"
    )
