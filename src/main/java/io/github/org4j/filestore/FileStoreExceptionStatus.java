package io.github.org4j.filestore;

import io.github.org4j.error.StatusThrowable;

/** The codes of the {@code GeneralException}s {@link FileStore} throws. */
public enum FileStoreExceptionStatus implements StatusThrowable {
  /** 500: the code named a bucket that isn't configured; the parameter names it. */
  FILE_STORE_BUCKET_UNKNOWN,
  /** 400: the upload is empty or couldn't be read. */
  FILE_STORE_FILE_EMPTY,
  /** 413: the file is larger than the bucket's {@code max-size}, the parameter. */
  FILE_STORE_FILE_TOO_LARGE,
  /** 415: the file's bytes aren't one of the bucket's types; the parameter lists them. */
  FILE_STORE_TYPE_NOT_ALLOWED,
  /** 404: there is no such file; the parameter is its id. */
  FILE_STORE_FILE_NOT_FOUND,
  /** 503: the store didn't answer. */
  FILE_STORE_STORAGE_UNAVAILABLE,
  /** 500: the stored file no longer matches its checksum or lost its metadata. */
  FILE_STORE_FILE_CORRUPT;

  @Override
  public String getType() {
    return name();
  }
}
