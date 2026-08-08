import { useEffect, useState } from "react";

type Theme = "light" | "dark";

function currentTheme(): Theme {
  const attr = document.documentElement.getAttribute("data-theme");
  if (attr === "light" || attr === "dark") {
    return attr;
  }
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
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
    </header>
  );
}
