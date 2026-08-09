// The API key the backend may require (X-API-Key). Stored in localStorage so it survives reloads.
// When the backend runs in keyless-dev mode, no key is set and no header is sent — it just works.

const STORAGE_KEY = "atlas-api-key";
export const API_KEY_HEADER = "X-API-Key";

export function getApiKey(): string | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value && value.length > 0 ? value : null;
  } catch {
    return null;
  }
}

export function setApiKey(key: string): void {
  const trimmed = key.trim();
  try {
    if (trimmed === "") {
      localStorage.removeItem(STORAGE_KEY);
    } else {
      localStorage.setItem(STORAGE_KEY, trimmed);
    }
  } catch {
    // ignore storage failures (private mode, etc.)
  }
}

/**
 * The auth header map for a given key — {@code {}} when there is no key, so absent-key requests send
 * no header at all (which keyless-dev backends accept). Pure, so it's unit-tested.
 */
export function headersForKey(key: string | null): Record<string, string> {
  return key && key.length > 0 ? { [API_KEY_HEADER]: key } : {};
}

/** Auth header map for the currently stored key. */
export function authHeaders(): Record<string, string> {
  return headersForKey(getApiKey());
}
