/** "PAST_DUE" → "Past due". */
export function humanize(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const text = value.toLowerCase().replace(/_/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Minor units (paise, cents) to a localised amount, e.g. 49900 INR → "₹499.00". */
export function money(amountMinor: number, currency: string, locale?: string): string {
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(amountMinor / 100);
  } catch {
    return `${(amountMinor / 100).toFixed(2)} ${currency}`;
  }
}
