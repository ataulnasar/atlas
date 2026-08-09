import { useEffect, useRef, useState } from "react";
import { getApiKey, setApiKey } from "../api/apiKey";

type Theme = "light" | "dark";

function currentTheme(): Theme {
  const attr = document.documentElement.getAttribute("data-theme");
  if (attr === "light" || attr === "dark") {
    return attr;
  }
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function GearIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="3" stroke="currentColor" strokeWidth="1.8" />
      <path
        d="M12 2.5v2.2M12 19.3v2.2M4.2 4.2l1.6 1.6M18.2 18.2l1.6 1.6M2.5 12h2.2M19.3 12h2.2M4.2 19.8l1.6-1.6M18.2 5.8l1.6-1.6"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
      />
    </svg>
  );
}

function ApiKeySettings() {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(() => getApiKey() ?? "");
  const [hasKey, setHasKey] = useState(() => getApiKey() !== null);
  const wrapperRef = useRef<HTMLDivElement>(null);

  // Close on outside click or Escape.
  useEffect(() => {
    if (!open) {
      return;
    }
    const onClick = (e: MouseEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onClick);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  function save() {
    setApiKey(draft);
    setHasKey(getApiKey() !== null);
    setOpen(false);
  }

  return (
    <div className="settings" ref={wrapperRef}>
      <button
        type="button"
        className="icon-button"
        onClick={() => {
          setDraft(getApiKey() ?? "");
          setOpen((o) => !o);
        }}
        aria-label="API key settings"
        aria-expanded={open}
        title="API key"
      >
        <GearIcon />
        {hasKey && <span className="key-dot" aria-hidden="true" />}
      </button>
      {open && (
        <div className="settings-popover" role="dialog" aria-label="API key settings">
          <p className="settings-label">API key</p>
          <p className="settings-help">
            Sent as <code>X-API-Key</code>. Only needed if this server requires it; leave empty for a
            keyless dev server.
          </p>
          <input
            className="settings-input"
            type="password"
            value={draft}
            placeholder="paste key…"
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && save()}
            aria-label="API key value"
          />
          <div className="settings-actions">
            <button type="button" className="settings-save" onClick={save}>
              Save
            </button>
            <button
              type="button"
              className="settings-clear"
              onClick={() => {
                setDraft("");
                setApiKey("");
                setHasKey(false);
              }}
            >
              Clear
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export function Header() {
  const [theme, setTheme] = useState<Theme>(currentTheme);

  useEffect(() => {
    document.documentElement.setAttribute("data-theme", theme);
    try {
      localStorage.setItem("atlas-theme", theme);
    } catch {
      // ignore storage failures (private mode, etc.)
    }
  }, [theme]);

  return (
    <header className="header">
      <div className="header-inner">
        <div className="brand">
          <span className="wordmark">ATLAS</span>
          <span className="tagline">Grounded answers from EU digital regulation</span>
        </div>
        <div className="header-actions">
          <ApiKeySettings />
          <button
            type="button"
            className="theme-toggle"
            onClick={() => setTheme((t) => (t === "dark" ? "light" : "dark"))}
            aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}
            title={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}
          >
            {theme === "dark" ? "Light" : "Dark"}
          </button>
        </div>
      </div>
    </header>
  );
}
