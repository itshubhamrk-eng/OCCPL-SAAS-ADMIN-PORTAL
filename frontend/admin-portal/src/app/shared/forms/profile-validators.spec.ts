import { GST, PAN, PHONE, WEBSITE, imageProblem } from './profile-validators';

const file = (name: string, type: string, size: number) =>
  new File([new Uint8Array(size)], name, { type });

describe('profile validators', () => {
  it('accepts well-formed GST, PAN, phone and website values', () => {
    expect(GST.test('27ABCDE1234F1Z5')).toBe(true);
    expect(PAN.test('ABCDE1234F')).toBe(true);
    expect(PHONE.test('+919876543210')).toBe(true);
    expect(WEBSITE.test('https://acme.example.com/about')).toBe(true);
  });

  it('rejects malformed values', () => {
    expect(GST.test('27abcde1234f1z5')).toBe(false);
    expect(PAN.test('ABCDE12345')).toBe(false);
    expect(PHONE.test('9876543210')).toBe(false);
    expect(WEBSITE.test('acme.example.com')).toBe(false);
  });

  it('flags images that are too large or of the wrong type', () => {
    expect(imageProblem('logo', file('a.png', 'image/png', 1000))).toBeNull();
    expect(imageProblem('logo', file('a.png', 'image/png', 600 * 1024))).toContain('too large');
    expect(imageProblem('logo', file('a.ico', 'image/x-icon', 1000))).toContain('must be');
    expect(imageProblem('favicon', file('favicon.ico', '', 1000))).toBeNull();
    expect(imageProblem('logo', file('a.svg', 'image/svg+xml', 100))).toContain('must be');
  });
});
