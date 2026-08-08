// Two real questions from the mini-golden set (atlas-evals/datasets/mini-golden.json):
// mg-01 (keyword-strong) and mg-06 (a genuinely hard AI Act classification question).
const EXAMPLES = [
  "What are the tasks of the data protection officer?",
  "What does the AI Act consider a high-risk AI system?",
];

export function EmptyState({ onPick }: { onPick: (question: string) => void }) {
  return (
    <div className="empty">
      <p className="empty-eyebrow">Retrieval-grounded assistant</p>
      <h1 className="empty-title">Ask about EU digital regulation.</h1>
      <p className="empty-lead">
        Atlas answers only from the documents it has ingested — the GDPR, the AI Act, the DSA/DMA,
        NIS2 and the rest of the EU digital rulebook — and cites the exact passage behind every
        claim. If the sources don’t cover your question, it says so rather than guessing.
      </p>
      <div className="examples">
        <p className="examples-label">Try one</p>
        <div className="example-grid">
          {EXAMPLES.map((q) => (
            <button key={q} type="button" className="example" onClick={() => onPick(q)}>
              {q}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
