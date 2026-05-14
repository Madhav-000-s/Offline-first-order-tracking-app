class AppError(Exception):
    status_code: int = 400

    def __init__(self, message: str) -> None:
        self.message = message
        super().__init__(message)


class EmailAlreadyExists(AppError):
    status_code = 409


class InvalidCredentials(AppError):
    status_code = 401


class InvalidRefreshToken(AppError):
    status_code = 401


class RefreshTokenReused(AppError):
    """The refresh token presented was already rotated away. Treated as theft:
    the entire token family is revoked, forcing a full re-login."""

    status_code = 401


class IdempotencyConflict(AppError):
    """Same Idempotency-Key, different request body."""

    status_code = 422


class InFlightConflict(AppError):
    """Same Idempotency-Key, request already in flight."""

    status_code = 409
