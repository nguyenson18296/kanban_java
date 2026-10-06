package com.kanban.common.exception;

import org.springframework.util.unit.DataSize;

/** Port of NestJS {@code PayloadTooLargeException} (HTTP 413). */
public class PayloadTooLargeException extends HttpException {
  public static final int STATUS = 413;
  public static final String DESCRIPTION = "Payload Too Large";

  /** {@code objectOrError} may be a String, a List (message array) or a Map (full body). */
  public PayloadTooLargeException(Object objectOrError) {
    super(createBody(objectOrError, DESCRIPTION, STATUS), STATUS);
  }

  /** The one "file too large" body, shared by AttachmentService and the multipart handler. */
  public static PayloadTooLargeException forFileSize(DataSize maxSize) {
    return new PayloadTooLargeException("File is too large. The maximum size is " + describe(maxSize) + ".");
  }

  private static String describe(DataSize size) {
    long bytes = size.toBytes();
    long megabyte = DataSize.ofMegabytes(1).toBytes();
    if (bytes % megabyte == 0) {
      return bytes / megabyte + " MB";
    }
    if (bytes % 1024 == 0) {
      return bytes / 1024 + " KB";
    }
    return bytes + " bytes";
  }
}
