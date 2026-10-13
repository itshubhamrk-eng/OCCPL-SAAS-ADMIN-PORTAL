import { HttpParams } from '@angular/common/http';
import { inject } from '@angular/core';
import { APP_CONFIG } from '../config/app-config';

/** Builds Core API URLs: {@code api('/companies', id)} → {@code <apiBaseUrl>/companies/<id>}. */
export function apiUrlBuilder(): (...segments: string[]) => string {
  const base = inject(APP_CONFIG).apiBaseUrl;
  return (...segments: string[]) =>
    base + segments.map((s) => (s.startsWith('/') ? s : '/' + encodeURIComponent(s))).join('');
}

export type QueryValue = string | number | boolean | null | undefined | readonly string[];

/** Query parameters without empty values; arrays become repeated parameters (e.g. sort). */
export function query(values: Record<string, QueryValue>): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(values)) {
    if (value === null || value === undefined || value === '') {
      continue;
    }
    if (Array.isArray(value)) {
      value.forEach((v) => (params = params.append(key, v)));
    } else {
      params = params.set(key, String(value));
    }
  }
  return params;
}

/** ETag for optimistic locking: the API's ETag is the quoted entity version. */
export function ifMatch(version: number): Record<string, string> {
  return { 'If-Match': `"${version}"` };
}
