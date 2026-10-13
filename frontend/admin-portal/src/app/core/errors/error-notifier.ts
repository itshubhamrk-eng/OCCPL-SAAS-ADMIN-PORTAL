import { Injectable, signal } from '@angular/core';
import { ApiError } from './api-error';

/** Holds the most recent request-level error for display in the shell's alert region. */
@Injectable({ providedIn: 'root' })
export class ErrorNotifier {
  private readonly current = signal<ApiError | null>(null);

  readonly error = this.current.asReadonly();

  notify(error: ApiError): void {
    this.current.set(error);
  }

  dismiss(): void {
    this.current.set(null);
  }
}
