package ads.api;

/**
 * An error answered with the API's one error shape, {@code {"error":{"code","message"}}},
 * and an HTTP status (400 bad input, 404 unknown, 405 wrong method, 413 too large).
 */
public final class ApiException extends Exception {
  private static final long serialVersionUID = 1L;

  final int status;
  final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
