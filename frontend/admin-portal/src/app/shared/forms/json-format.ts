/** Pretty-prints JSON text with two-space indentation; null when the text is not valid JSON. */
export function beautifyJson(text: string): string | null {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return null;
  }
}
