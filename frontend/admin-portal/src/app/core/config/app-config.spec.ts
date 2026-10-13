import { loadAppConfig, parseAppConfig } from './app-config';

describe('parseAppConfig', () => {
  const valid = {
    apiBaseUrl: 'https://api.occpl.test/api/v1/',
    oidc: { issuer: 'https://id.occpl.test/realms/occpl', clientId: 'occpl-admin-portal' },
  };

  it('accepts a valid configuration and trims trailing slashes', () => {
    expect(parseAppConfig(valid).apiBaseUrl).toBe('https://api.occpl.test/api/v1');
  });

  it('rejects relative or non-http URLs', () => {
    expect(() => parseAppConfig({ ...valid, apiBaseUrl: '/api' })).toThrow(/apiBaseUrl/);
    expect(() => parseAppConfig({ ...valid, apiBaseUrl: 'javascript:alert(1)' })).toThrow(
      /apiBaseUrl/,
    );
  });

  it('requires an OIDC client id', () => {
    expect(() => parseAppConfig({ ...valid, oidc: { ...valid.oidc, clientId: ' ' } })).toThrow(
      /clientId/,
    );
  });

  it('rejects non-object input', () => {
    expect(() => parseAppConfig(null)).toThrow();
    expect(() => parseAppConfig([])).toThrow();
  });
});

describe('loadAppConfig', () => {
  it('fails clearly when the file cannot be loaded', async () => {
    const fetchFn = (() => Promise.resolve(new Response('', { status: 404 }))) as typeof fetch;
    await expect(loadAppConfig(fetchFn)).rejects.toThrow(/HTTP 404/);
  });
});
