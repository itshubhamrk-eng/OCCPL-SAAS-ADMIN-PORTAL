import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import {
  RouteReuseStrategy,
  TitleStrategy,
  provideRouter,
  withComponentInputBinding,
  withRouterConfig,
} from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { APP_CONFIG, AppConfig } from './core/config/app-config';
import { apiErrorInterceptor } from './core/http/api-error.interceptor';
import { correlationIdInterceptor } from './core/http/correlation-id.interceptor';
import { ParamAwareRouteReuseStrategy } from './core/route-reuse';
import { PortalTitleStrategy } from './core/title-strategy';

export function createAppConfig(runtimeConfig: AppConfig): ApplicationConfig {
  return {
    providers: [
      provideBrowserGlobalErrorListeners(),
      { provide: APP_CONFIG, useValue: runtimeConfig },
      // Child pages receive :companyId as an input too.
      provideRouter(
        routes,
        withComponentInputBinding(),
        withRouterConfig({ paramsInheritanceStrategy: 'always' }),
      ),
      { provide: TitleStrategy, useClass: PortalTitleStrategy },
      { provide: RouteReuseStrategy, useClass: ParamAwareRouteReuseStrategy },
      // Order matters: the error interceptor sees the raw response after the auth interceptor.
      provideHttpClient(
        withFetch(),
        withInterceptors([correlationIdInterceptor, apiErrorInterceptor, authInterceptor]),
      ),
    ],
  };
}
