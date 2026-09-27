# zetteldraw web overview

Every notebook and page from the Boox on one screen. Setup, the endpoints, and how thumbnails work are in the [root README](../README.md#web-overview-web).

```bash
npm ci
ZD_SERVER_URL=http://127.0.0.1:8787 npm run dev   # http://localhost:43917, sign in with the device token
npm test && npm run lint && npm run typecheck
```

| Env | Default | |
| --- | --- | --- |
| `ZD_SERVER_URL` | `http://127.0.0.1:8787` | Sync server, as seen from the Next.js server |
| `ZD_DEVICE_TOKEN` | unset | If set, used for every call and there is no sign-in (the site is public) |
| `ZD_DEV_ORIGINS` | unset | Extra hosts allowed to open the dev server, comma-separated |
