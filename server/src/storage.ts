import {
  CreateBucketCommand,
  GetObjectCommand,
  HeadBucketCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
  type S3ClientConfig,
} from "@aws-sdk/client-s3";
import type { Config } from "./config.js";

/** Content-addressed ink blobs: key ink/<sha256>. Objects are immutable. */
export interface InkStorage {
  has(hash: string): Promise<boolean>;
  put(hash: string, bytes: Buffer): Promise<void>;
  get(hash: string): Promise<Buffer>;
}

export function inkKey(hash: string): string {
  return `ink/${hash}`;
}

/**
 * Client options that always carry the keys from config.
 * `credentials: undefined` selects the AWS default chain, which only reads
 * AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY and then throws
 * CredentialsProviderError. Render sets S3_ACCESS_KEY_ID / S3_SECRET_ACCESS_KEY.
 */
export function s3ClientOptions(config: Config["s3"]): S3ClientConfig {
  const accessKeyId = config.accessKeyId?.trim() ?? "";
  const secretAccessKey = config.secretAccessKey?.trim() ?? "";
  if (!accessKeyId || !secretAccessKey) {
    throw new Error(
      "S3 credentials are required. Set S3_ACCESS_KEY_ID and S3_SECRET_ACCESS_KEY, or S3_ACCESS_KEY and S3_SECRET_KEY.",
    );
  }
  const credentials = { accessKeyId, secretAccessKey };
  return {
    region: config.region,
    endpoint: config.endpoint,
    forcePathStyle: Boolean(config.endpoint),
    credentials: async () => credentials,
  };
}

function isMissingBucket(err: unknown): boolean {
  if (!err || typeof err !== "object") {
    return false;
  }
  const e = err as { name?: string; Code?: string; code?: string; $metadata?: { httpStatusCode?: number } };
  const code = e.name || e.Code || e.code || "";
  if (code === "NotFound" || code === "NoSuchBucket") {
    return true;
  }
  return e.$metadata?.httpStatusCode === 404;
}

export class S3InkStorage implements InkStorage {
  constructor(
    private readonly s3: S3Client,
    private readonly bucket: string,
  ) {}

  static fromConfig(config: Config["s3"]): S3InkStorage {
    return new S3InkStorage(new S3Client(s3ClientOptions(config)), config.bucket);
  }

  async ensureBucket(): Promise<void> {
    try {
      await this.s3.send(new HeadBucketCommand({ Bucket: this.bucket }));
    } catch (err) {
      // A credential failure must not fall through to CreateBucket. That second
      // call is what left the process with an uncaught CredentialsProviderError.
      if (!isMissingBucket(err)) {
        throw err;
      }
      await this.s3.send(new CreateBucketCommand({ Bucket: this.bucket }));
    }
  }

  async has(hash: string): Promise<boolean> {
    try {
      await this.s3.send(new HeadObjectCommand({ Bucket: this.bucket, Key: inkKey(hash) }));
      return true;
    } catch (err) {
      const status = (err as { $metadata?: { httpStatusCode?: number } }).$metadata?.httpStatusCode;
      if (status === 404 || (err as Error).name === "NotFound") {
        return false;
      }
      throw err;
    }
  }

  async put(hash: string, bytes: Buffer): Promise<void> {
    if (await this.has(hash)) {
      return;
    }
    await this.s3.send(
      new PutObjectCommand({
        Bucket: this.bucket,
        Key: inkKey(hash),
        Body: bytes,
        ContentType: "application/octet-stream",
      }),
    );
  }

  async get(hash: string): Promise<Buffer> {
    const res = await this.s3.send(new GetObjectCommand({ Bucket: this.bucket, Key: inkKey(hash) }));
    const body = res.Body;
    if (!body) {
      throw new Error(`empty object ${inkKey(hash)}`);
    }
    return Buffer.from(await body.transformToByteArray());
  }
}
