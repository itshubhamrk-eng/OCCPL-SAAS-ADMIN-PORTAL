import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { APP_CONFIG } from '../config/app-config';
import { isApiRequest } from '../http/correlation-id.interceptor';
import { AuthService } from './auth.service';

let redirecting = false;

/** Sends the access token to the Core API only; a 401 from the API starts a new sign-in once. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const { apiBaseUrl } = inject(APP_CONFIG);
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!isApiRequest(req.url, apiBaseUrl)) {
    return next(req);
  }
  const token = auth.accessToken();
  const request = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
  return next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && !redirecting) {
        redirecting = true;
        void auth.login(router.url).finally(() => (redirecting = false));
      }
      return throwError(() => error);
    }),
  );
};
