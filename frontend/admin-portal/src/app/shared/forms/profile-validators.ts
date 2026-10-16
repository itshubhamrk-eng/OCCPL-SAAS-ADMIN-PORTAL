import { ValidatorFn, Validators } from '@angular/forms';

/** Same rules as the API (CompanyRequests); the API remains the authority. */
export const PHONE = /^\+[1-9][0-9]{6,14}$/;
export const GST = /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/;
export const PAN = /^[A-Z]{5}[0-9]{4}[A-Z]$/;
export const WEBSITE = /^https?:\/\/[^\s/$.?#][^\s]{2,250}$/;
export const POSTAL = /^[A-Za-z0-9][A-Za-z0-9 -]{1,18}[A-Za-z0-9]$/;

const text = (validator: ValidatorFn): [string, ValidatorFn] => ['', validator];

export const profileControls = () => ({
  website: text(Validators.pattern(WEBSITE)),
  addressLine1: text(Validators.maxLength(200)),
  addressLine2: text(Validators.maxLength(200)),
  city: text(Validators.maxLength(100)),
  state: text(Validators.maxLength(100)),
  postalCode: text(Validators.pattern(POSTAL)),
  gstNumber: text(Validators.pattern(GST)),
  panNumber: text(Validators.pattern(PAN)),
});

export const IMAGE_LIMITS = {
  logo: {
    bytes: 512 * 1024,
    types: ['image/png', 'image/jpeg', 'image/webp'],
    label: 'PNG, JPEG or WebP, up to 512 KB',
  },
  favicon: {
    bytes: 128 * 1024,
    types: ['image/png', 'image/jpeg', 'image/webp', 'image/x-icon', 'image/vnd.microsoft.icon'],
    label: 'PNG, JPEG, WebP or ICO, up to 128 KB',
  },
} as const;

/** A message when the chosen file is obviously unsuitable (the API checks the real content). */
export function imageProblem(kind: 'logo' | 'favicon', file: File): string | null {
  const limit = IMAGE_LIMITS[kind];
  if (file.size > limit.bytes) {
    return `The ${kind} is too large (${limit.label}).`;
  }
  const ok =
    (limit.types as readonly string[]).includes(file.type) ||
    (kind === 'favicon' && /\.ico$/i.test(file.name));
  return ok ? null : `The ${kind} must be ${limit.label}.`;
}
