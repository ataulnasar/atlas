import { describe, it, expect } from "vitest";
import { headersForKey, API_KEY_HEADER } from "./apiKey";

describe("headersForKey", () => {
  it("sends the X-API-Key header when a key is set", () => {
    expect(headersForKey("secret")).toEqual({ [API_KEY_HEADER]: "secret" });
  });

  it("sends no header when there is no key (keyless-dev backends accept absent header)", () => {
    expect(headersForKey(null)).toEqual({});
    expect(headersForKey("")).toEqual({});
  });
});
