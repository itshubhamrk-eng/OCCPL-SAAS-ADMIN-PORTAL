import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { APP_CONFIG } from '../config/app-config';
import { toApiError } from '../errors/api-error';
import { ErrorNotifier } from '../errors/error-notifier';
import { isApiRequest } from './correlation-id.interceptor';

/** Set to true on a request whose caller displays errors itself (e.g. inline form validation). */
export const SUPPRESS_GLOBAL_ERROR = new HttpContextToken<boolean>(() => false);

/**
 * Converts Core API failures to {@link ApiError}, reports them centrally unless suppressed and
 * rethrows the normalised error so callers can still react.
 */
export const apiErrorInterceptor: HttpInterceptorFn = (req, next) => {
  const { apiBaseUrl } = inject(APP_CONFIG);
  const notifier = inject(ErrorNotifier);
  if (!isApiRequest(req.url, apiBaseUrl)) {
    return next(req);
  }
  return next(req).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse)) {
        return throwError(() => error);
      }
      const apiError = toApiError(error);
      const isFieldValidation = apiError.fieldErrors.length > 0;
      if (!req.context.get(SUPPRESS_GLOBAL_ERROR) && !isFieldValidation) {
        notifier.notify(apiError);
      }
      return throwError(() => apiError);
    }),
  );
};
