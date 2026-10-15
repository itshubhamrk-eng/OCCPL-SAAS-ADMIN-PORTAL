import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { ApiError } from '../errors/api-error';
import { ErrorNotifier } from '../errors/error-notifier';
import { SUPPRESS_GLOBAL_ERROR, apiErrorInterceptor } from './api-error.interceptor';
import { CORRELATION_ID_HEADER, correlationIdInterceptor } from './correlation-id.interceptor';

const config: AppConfig = {
  apiBaseUrl: 'https://api.occpl.test/api/v1',
  oidc: { issuer: 'https://id.occpl.test/realms/occpl', clientId: 'occpl-admin-portal' },
};

describe('HTTP interceptors', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let notifier: ErrorNotifier;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: config },
        provideHttpClient(withInterceptors([correlationIdInterceptor, apiErrorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    notifier = TestBed.inject(ErrorNotifier);
  });

  afterEach(() => backend.verify());

  it('adds a correlation id to API requests only', () => {
    http.get(`${config.apiBaseUrl}/companies`).subscribe();
    http.get('https://cdn.example.com/logo.svg').subscribe();
    http.get('https://api.occpl.test/api/v10/other').subscribe();

    const apiReq = backend.expectOne(`${config.apiBaseUrl}/companies`);
    expect(apiReq.request.headers.get(CORRELATION_ID_HEADER)).toMatch(/^[0-9a-f-]{36}$/);
    const external = backend.expectOne('https://cdn.example.com/logo.svg');
    expect(external.request.headers.has(CORRELATION_ID_HEADER)).toBe(false);
    const lookalike = backend.expectOne('https://api.occpl.test/api/v10/other');
    expect(lookalike.request.headers.has(CORRELATION_ID_HEADER)).toBe(false);
    [apiReq, external, lookalike].forEach((r) => r.flush({}));
  });

  it('normalises problem details and notifies globally', async () => {
    const result = firstValueFrom(http.get(`${config.apiBaseUrl}/companies/x`));
    backend.expectOne(`${config.apiBaseUrl}/companies/x`).flush(
      {
        status: 404,
        code: 'NOT_FOUND',
        detail: 'Company not found.',
        correlationId: 'abc-12345',
      },
      { status: 404, statusText: 'Not Found' },
    );

    const error = (await result.catch((e: unknown) => e)) as ApiError;
    expect(error).toEqual({
      status: 404,
      code: 'NOT_FOUND',
      message: 'Company not found.',
      correlationId: 'abc-12345',
      fieldErrors: [],
    });
    expect(notifier.error()?.code).toBe('NOT_FOUND');
  });

  it('leaves field validation errors to the form instead of the global notifier', async () => {
    const result = firstValueFrom(http.post(`${config.apiBaseUrl}/companies`, {}));
    backend.expectOne(`${config.apiBaseUrl}/companies`).flush(
      {
        code: 'VALIDATION_ERROR',
        errors: [{ field: 'legalName', code: 'NotBlank', message: 'must not be blank' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );

    const error = (await result.catch((e: unknown) => e)) as ApiError;
    expect(error.fieldErrors[0]?.field).toBe('legalName');
    expect(notifier.error()).toBeNull();
  });

  it('respects SUPPRESS_GLOBAL_ERROR', async () => {
    const context = new HttpContext().set(SUPPRESS_GLOBAL_ERROR, true);
    const result = firstValueFrom(http.get(`${config.apiBaseUrl}/me`, { context }));
    backend
      .expectOne(`${config.apiBaseUrl}/me`)
      .flush({ code: 'FORBIDDEN' }, { status: 403, statusText: 'Forbidden' });

    await result.catch(() => undefined);
    expect(notifier.error()).toBeNull();
  });

  it('reports network failures with a friendly message', async () => {
    const result = firstValueFrom(http.get(`${config.apiBaseUrl}/me`));
    backend.expectOne(`${config.apiBaseUrl}/me`).error(new ProgressEvent('error'));

    const error = (await result.catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('NETWORK_ERROR');
    expect(notifier.error()?.code).toBe('NETWORK_ERROR');
  });
});
