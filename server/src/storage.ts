import {
  CreateBucketCommand,
  GetObjectCommand,
  HeadBucketCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
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

export class S3InkStorage implements InkStorage {
  constructor(
    private readonly s3: S3Client,
    private readonly bucket: string,
  ) {}

  static fromConfig(config: Config["s3"]): S3InkStorage {
    const s3 = new S3Client({
      region: config.region,
      endpoint: config.endpoint,
      forcePathStyle: Boolean(config.endpoint),
      credentials:
        config.accessKeyId && config.secretAccessKey
          ? { accessKeyId: config.accessKeyId, secretAccessKey: config.secretAccessKey }
          : undefined,
    });
    return new S3InkStorage(s3, config.bucket);
  }

  async ensureBucket(): Promise<void> {
    try {
      await this.s3.send(new HeadBucketCommand({ Bucket: this.bucket }));
    } catch {
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
