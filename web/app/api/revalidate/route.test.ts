import { createHash } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";

const { revalidateTag } = vi.hoisted(() => ({ revalidateTag: vi.fn() }));

vi.mock("next/cache", () => ({ revalidateTag }));

import { NextRequest } from "next/server";
import { POST } from "./route";

function requestWithSecret(secret?: string): NextRequest {
  return new NextRequest("https://web.test/api/revalidate", {
    method: "POST",
    headers: secret ? { "X-Revalidate-Secret": secret } : undefined,
    body: JSON.stringify({ tag: "posts" }),
  });
}

afterEach(() => {
  delete process.env.REVALIDATE_SECRET;
  revalidateTag.mockReset();
});

describe("POST /api/revalidate", () => {
  it("rechaza un secreto ausente o invalido", async () => {
    process.env.REVALIDATE_SECRET = "test-secret";

    const missing = await POST(requestWithSecret());
    const invalid = await POST(requestWithSecret("not-the-hash"));

    expect(missing.status).toBe(401);
    expect(invalid.status).toBe(401);
    expect(revalidateTag).not.toHaveBeenCalled();
  });

  it("revalida posts con el SHA-256 correcto", async () => {
    const secret = "test-secret";
    process.env.REVALIDATE_SECRET = secret;
    const hash = createHash("sha256").update(secret).digest("hex");

    const response = await POST(requestWithSecret(hash));

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({
      revalidated: true,
      tag: "posts",
    });
    expect(revalidateTag).toHaveBeenCalledWith("posts", { expire: 0 });
  });
});
