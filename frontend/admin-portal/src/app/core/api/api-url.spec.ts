import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TEST_CONFIG } from '../../testing/fixtures';
import { APP_CONFIG } from '../config/app-config';
import { CompaniesApi } from './companies.api';
import { Company } from './models';
import { query } from './api-url';

describe('API helpers', () => {
  it('drops empty query values and repeats arrays', () => {
    const params = query({
      q: '',
      status: 'ACTIVE',
      page: 0,
      size: undefined,
      sort: ['name,asc', 'createdAt,desc'],
    });
    expect(params.toString()).toBe('status=ACTIVE&page=0&sort=name,asc&sort=createdAt,desc');
  });

  it('encodes path segments and sends If-Match with the entity version', () => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    const api = TestBed.inject(CompaniesApi);
    const http = TestBed.inject(HttpTestingController);
    api.update({ id: 'a/b', version: 7 } as Company, { displayName: 'New' }).subscribe();
    const req = http.expectOne(`${TEST_CONFIG.apiBaseUrl}/companies/a%2Fb`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.headers.get('If-Match')).toBe('"7"');
    http.verify();
  });
});
