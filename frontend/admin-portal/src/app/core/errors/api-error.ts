import { HttpErrorResponse } from '@angular/common/http';

/** RFC 9457 problem document returned by the Core Platform (API_SPEC.md §2). */
export interface ProblemDetail {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly code?: string;
  readonly correlationId?: string;
  readonly errors?: readonly FieldViolation[];
}

export interface FieldViolation {
  readonly field: string;
  readonly code: string;
  readonly message: string;
}

/** Normalised error passed to components and the error notifier. */
export interface ApiError {
  readonly status: number;
  readonly code: string;
  readonly message: string;
  readonly correlationId?: string;
  readonly fieldErrors: readonly FieldViolation[];
}

export function toApiError(error: HttpErrorResponse): ApiError {
  if (error.status === 0) {
    return {
      status: 0,
      code: 'NETWORK_ERROR',
      message: 'The server could not be reached. Check your connection and try again.',
      fieldErrors: [],
    };
  }
  const problem = isProblemDetail(error.error) ? error.error : undefined;
  return {
    status: error.status,
    code: problem?.code ?? 'HTTP_' + error.status,
    message: problem?.detail ?? problem?.title ?? 'The request failed. Please try again.',
    correlationId: problem?.correlationId ?? error.headers?.get('X-Correlation-Id') ?? undefined,
    fieldErrors: problem?.errors ?? [],
  };
}

function isProblemDetail(body: unknown): body is ProblemDetail {
  return (
    typeof body === 'object' &&
    body !== null &&
    ('code' in body || 'detail' in body || 'title' in body)
  );
}
