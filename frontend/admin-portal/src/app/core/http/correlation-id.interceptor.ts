import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { APP_CONFIG } from '../config/app-config';

export const CORRELATION_ID_HEADER = 'X-Correlation-Id';

/** Adds a correlation id to Core API requests so browser actions can be traced in server logs. */
export const correlationIdInterceptor: HttpInterceptorFn = (req, next) => {
  const { apiBaseUrl } = inject(APP_CONFIG);
  if (!isApiRequest(req.url, apiBaseUrl) || req.headers.has(CORRELATION_ID_HEADER)) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { [CORRELATION_ID_HEADER]: crypto.randomUUID() } }));
};

export function isApiRequest(url: string, apiBaseUrl: string): boolean {
  return url === apiBaseUrl || url.startsWith(apiBaseUrl + '/') || url.startsWith(apiBaseUrl + '?');
}
