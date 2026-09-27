const rtf = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
const UNITS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ["year", 365 * 86_400_000],
  ["month", 30 * 86_400_000],
  ["week", 7 * 86_400_000],
  ["day", 86_400_000],
  ["hour", 3_600_000],
  ["minute", 60_000],
];

export function relativeTime(ms: number | null, now = Date.now()): string {
  if (ms === null) return "never";
  const diff = ms - now;
  for (const [unit, size] of UNITS) {
    if (Math.abs(diff) >= size) {
      return rtf.format(Math.round(diff / size), unit);
    }
  }
  return "just now";
}

export function dateTime(ms: number): string {
  return new Date(ms).toLocaleString("en", { dateStyle: "medium", timeStyle: "short" });
}

export function pagesLabel(n: number): string {
  return n === 1 ? "1 page" : `${n} pages`;
}
