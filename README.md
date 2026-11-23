# filestore

Files for any module, in buckets of an S3-compatible store (SeaweedFS locally, any S3 elsewhere).
A module keeps only a file's id; what the file is travels with the object as its metadata. The
buckets are infrastructure's: the app only checks at startup that each one exists.

```groovy
implementation libs.filestore
```

```java
var stored = files.store("partner-documents", multipartFile);
var content = files.read("partner-documents", stored.id()); // small files, whole

try (var streaming = files.openStream("partner-documents", stored.id())) {
    streaming.transferTo(response.getOutputStream()); // large files, never whole in memory
}
files.transferTo("partner-documents", stored.id(), response.getOutputStream()); // the same, shorter

files.delete("partner-documents", stored.id());
```

## What a stored file is

| Field | From |
|---|---|
| `id` | a random UUID, the object's key |
| `fileName` | the upload's name, its directories dropped, characters other than letters, digits, space and `._()-` replaced by `_`, at most 200 characters; any alphabet is kept |
| `contentType` | the file's own bytes, never the upload's claim |
| `size`, `sha256` | the bytes stored |
| `uploadedAt` | the time it was stored |

## What a bucket takes

| Type | Taken when |
|---|---|
| `application/pdf` | it starts with `%PDF-` and ends with `%%EOF` |
| `image/png`, `image/jpeg` | it has the format's signature and decodes as an image of at most 100 megapixels, decoded at about one megapixel |
| anything else (SVG, HTML, ...) | never |

A file that also holds a ZIP archive (a polyglot) is no type at all. Reading a file back checks
its checksum: whole at once for `read`, at the end of the stream for `openStream` and `transferTo`.

## Properties

| Property | Default | What |
|---|---|---|
| `org4j.filestore.storage.endpoint` | none | the store's URL; setting it turns the library on |
| `org4j.filestore.storage.region` | none, required | the region requests are signed for |
| `org4j.filestore.storage.credentials` | `keys` | `keys`: the access and secret key, both required; `default-chain`: AWS's default credentials chain (an instance or container role), only when chosen |
| `org4j.filestore.storage.access-key` | none | the app's access key |
| `org4j.filestore.storage.secret-key` | none | its secret key, from Vault |
| `org4j.filestore.storage.path-style` | `false` | the bucket in the path, as SeaweedFS and MinIO want |
| `org4j.filestore.storage.api-call-timeout` | `30s` | one call, retries included |
| `org4j.filestore.storage.api-call-attempt-timeout` | `10s` | one attempt of a call |
| `org4j.filestore.buckets.<name>.allowed-types` | none, required | the types above the bucket takes |
| `org4j.filestore.buckets.<name>.max-size` | none, required | the largest file it takes, more than 0 |

## Errors

| Code | Status | When |
|---|---|---|
| `FILE_STORE_FILE_EMPTY` | 400 | the upload is empty or can't be read |
| `FILE_STORE_FILE_TOO_LARGE` | 413 | larger than the bucket's `max-size` |
| `FILE_STORE_TYPE_NOT_ALLOWED` | 415 | its bytes aren't one of the bucket's types; the message lists them |
| `FILE_STORE_FILE_NOT_FOUND` | 404 | no such file |
| `FILE_STORE_BUCKET_UNKNOWN` | 500 | the code named a bucket that isn't configured |
| `FILE_STORE_FILE_CORRUPT` | 500 | the stored bytes no longer match the checksum, or the object lost its metadata |
| `FILE_STORE_STORAGE_UNAVAILABLE` | 503 | the store didn't answer or refused the call |

`delete` throws only `FILE_STORE_BUCKET_UNKNOWN`: a store that doesn't answer is logged, and the
file stays behind.

## Health

With the actuator, `fileStore`: UP while every configured bucket answers a HeadBucket with the app's
credentials (3 s limit). DOWN lists each failing bucket with the store's error code, or its HTTP
status (403: the signature or key is rejected; 404: no such bucket). Details show only to callers
who may see them (`management.endpoint.health.show-details`). Without the actuator there is no
indicator, and the rest works the same.

## Beans you can replace

| Bean | |
|---|---|
| `S3Client` | any bean of the type |
| `FileStore` | any bean of the type |
| `fileStoreHealthIndicator` | a bean of that name |

## Tests

- `./gradlew test`: `FileStoreTest` is the specification, one nested group each for storing,
  reading, deleting and startup. `FileTypesTest` covers types from bytes, polyglots, image bombs
  and names; `FileStorePropertiesTest` the property checks and an app without the actuator.
- `./gradlew integrationTest`: a real SeaweedFS as an identity that may only read and write its
  buckets (nothing lists them): store, read, stream, delete, names in any alphabet, the rules, a
  missing bucket, a file changed behind the store's back, and health with right and wrong keys.
