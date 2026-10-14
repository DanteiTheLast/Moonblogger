import { isIP } from "node:net";

export function canonicalIp(value: string): string | null {
  const input = value.trim();
  if (!isIP(input)) return null;
  if (!input.includes(":")) return input.split(".").map(Number).join(".");
  const parts = input.toLowerCase().split("::");
  if (parts.length > 2) return null;
  const left = parts[0] ? parts[0].split(":") : [];
  const right = parts[1] ? parts[1].split(":") : [];
  const expanded = parts.length === 2 ? [...left, ...Array(8 - left.length - right.length).fill("0"), ...right] : left;
  if (expanded.length !== 8) return null;
  const nums = expanded.map((part) => Number.parseInt(part, 16));
  if (nums.some((n) => !Number.isInteger(n) || n < 0 || n > 0xffff)) return null;
  let bestStart = -1, bestLength = 0;
  for (let i = 0; i < 8;) { if (nums[i] !== 0) { i++; continue; } let j = i; while (j < 8 && nums[j] === 0) j++; if (j - i > bestLength) { bestStart = i; bestLength = j - i; } i = j; }
  const rendered = nums.map((n) => n.toString(16));
  if (bestLength >= 2) rendered.splice(bestStart, bestLength, "");
  if (bestStart === 0 && bestLength === 8) return "::";
  return rendered.join(":").replace(/^:/, "::").replace(/:$/, "::");
}

