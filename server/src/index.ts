import { existsSync } from "node:fs";
import { buildApp } from "./app.js";
import { loadConfig } from "./config.js";
import { createPool, migrate } from "./db.js";
import { S3InkStorage } from "./storage.js";
import { Thumbnailer } from "./thumbs.js";

if (existsSync(".env")) {
  process.loadEnvFile(".env");
}

const config = loadConfig();
const pool = createPool(config.databaseUrl, config.databaseCaCert);
const schemaVersion = await migrate(pool, config.migrationsDir);
const storage = S3InkStorage.fromConfig(config.s3);
await storage.ensureBucket();

let app: ReturnType<typeof buildApp> | undefined;
const thumbs = new Thumbnailer(storage, config.page, (err, inkHash) =>
  app?.log.error({ err, inkHash }, "thumbnail render failed"),
);
app = buildApp({ pool, storage, schemaVersion, deviceTokens: config.deviceTokens, thumbs });
await app.listen({ host: config.host, port: config.port });
app.log.info({ schemaVersion }, "zetteldraw sync ready");

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, async () => {
    await app.close();
    await pool.end();
    process.exit(0);
  });
}
