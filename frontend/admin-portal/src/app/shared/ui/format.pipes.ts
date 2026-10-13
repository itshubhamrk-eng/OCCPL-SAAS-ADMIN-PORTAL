import { Pipe, PipeTransform } from '@angular/core';
import { humanize, money } from './format';

@Pipe({ name: 'humanize' })
export class HumanizePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return humanize(value);
  }
}

@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(amountMinor: number | null | undefined, currency: string): string {
    return amountMinor === null || amountMinor === undefined ? '—' : money(amountMinor, currency);
  }
}
