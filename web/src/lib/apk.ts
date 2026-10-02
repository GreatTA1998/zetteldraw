const REPO = process.env.ZD_APK_REPO ?? "GreatTA1998/zetteldraw";
const ASSET = "zetteldraw.apk";
const META_TTL_MS = 5 * 60 * 1000;

type Release = { tag: string; url: string; size: number | null };

let cached: { release: Release; at: number } | null = null;

function githubHeaders(): HeadersInit {
  const headers: Record<string, string> = {
    accept: "application/vnd.github+json",
    "user-agent": "zetteldraw-web",
  };
  if (process.env.GITHUB_TOKEN) headers.authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  return headers;
}

async function fromApi(): Promise<Release> {
  const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
    headers: githubHeaders(),
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`GitHub API ${res.status}`);
  const body = (await res.json()) as {
    tag_name: string;
    assets: { name: string; size: number; browser_download_url: string }[];
  };
  const asset = body.assets.find((a) => a.name === ASSET);
  if (!asset) throw new Error(`release ${body.tag_name} has no ${ASSET}`);
  return { tag: body.tag_name, url: asset.browser_download_url, size: asset.size };
}

// Unlike the API, the github.com redirect isn't subject to the 60/hour unauthenticated limit
// that Render's shared egress IPs can exhaust.
async function fromRedirect(): Promise<Release> {
  const res = await fetch(`https://github.com/${REPO}/releases/latest/download/${ASSET}`, {
    redirect: "manual",
    cache: "no-store",
  });
  const location = res.headers.get("location") ?? "";
  const tag = location.match(/\/releases\/download\/([^/]+)\//)?.[1];
  if (!tag) throw new Error(`no release redirect (${res.status})`);
  return { tag: decodeURIComponent(tag), url: new URL(location, res.url).toString(), size: null };
}

async function latestRelease(): Promise<Release> {
  if (cached && Date.now() - cached.at < META_TTL_MS) return cached.release;
  let release: Release;
  try {
    release = await fromApi();
  } catch {
    release = await fromRedirect();
  }
  cached = { release, at: Date.now() };
  return release;
}

function apkHeaders(release: Release, length: string | null): Headers {
  const name = `zetteldraw-${release.tag.replace(/[^\w.-]/g, "_")}.apk`;
  const headers = new Headers({
    "content-type": "application/vnd.android.package-archive",
    "content-disposition": `attachment; filename="${name}"`,
    "cache-control": "no-store",
    "x-zetteldraw-release": release.tag,
  });
  if (length) headers.set("content-length", length);
  return headers;
}

function unavailable(error: unknown): Response {
  cached = null;
  const message = error instanceof Error ? error.message : String(error);
  return new Response(`The Zetteldraw APK is unavailable right now (${message}). Try again in a minute.\n`, {
    status: 502,
    headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" },
  });
}

export async function apkGet(): Promise<Response> {
  try {
    const release = await latestRelease();
    const upstream = await fetch(release.url, { headers: { "user-agent": "zetteldraw-web" }, cache: "no-store" });
    if (!upstream.ok || !upstream.body) throw new Error(`asset download ${upstream.status}`);
    const length = upstream.headers.get("content-length") ?? (release.size ? String(release.size) : null);
    return new Response(upstream.body, { status: 200, headers: apkHeaders(release, length) });
  } catch (error) {
    return unavailable(error);
  }
}

export async function apkHead(): Promise<Response> {
  try {
    const release = await latestRelease();
    let length = release.size ? String(release.size) : null;
    if (!length) {
      const probe = await fetch(release.url, { method: "HEAD", cache: "no-store" });
      length = probe.headers.get("content-length");
    }
    return new Response(null, { status: 200, headers: apkHeaders(release, length) });
  } catch (error) {
    return unavailable(error);
  }
}
