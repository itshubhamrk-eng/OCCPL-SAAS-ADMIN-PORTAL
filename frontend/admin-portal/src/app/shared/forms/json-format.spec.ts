import { beautifyJson } from './json-format';

describe('beautifyJson', () => {
  it('indents compact JSON with two spaces', () => {
    expect(beautifyJson('{"a":1,"b":{"c":[1,2]}}')).toBe(
      '{\n  "a": 1,\n  "b": {\n    "c": [\n      1,\n      2\n    ]\n  }\n}',
    );
  });

  it('keeps the same content when already formatted', () => {
    const text = '{\n  "schemaVersion": 1\n}';
    expect(beautifyJson(text)).toBe(text);
  });

  it('returns null for invalid or empty text', () => {
    expect(beautifyJson('{"a":')).toBeNull();
    expect(beautifyJson('')).toBeNull();
  });
});
