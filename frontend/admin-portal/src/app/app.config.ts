import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { APP_CONFIG, AppConfig } from './core/config/app-config';
import { apiErrorInterceptor } from './core/http/api-error.interceptor';
import { correlationIdInterceptor } from './core/http/correlation-id.interceptor';

export function createAppConfig(runtimeConfig: AppConfig): ApplicationConfig {
  return {
    providers: [
      provideBrowserGlobalErrorListeners(),
      { provide: APP_CONFIG, useValue: runtimeConfig },
      provideRouter(routes, withComponentInputBinding()),
      provideHttpClient(
        withFetch(),
        withInterceptors([correlationIdInterceptor, apiErrorInterceptor]),
      ),
    ],
  };
}
