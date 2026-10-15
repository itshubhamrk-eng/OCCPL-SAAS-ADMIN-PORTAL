import { ActivatedRouteSnapshot, BaseRouteReuseStrategy } from '@angular/router';

/**
 * Re-creates components when a route's path parameters change (e.g. switching from one company or
 * product to another), so pages never show data of the previous entity while reloading.
 */
export class ParamAwareRouteReuseStrategy extends BaseRouteReuseStrategy {
  override shouldReuseRoute(
    future: ActivatedRouteSnapshot,
    current: ActivatedRouteSnapshot,
  ): boolean {
    if (future.routeConfig !== current.routeConfig) {
      return false;
    }
    const a = future.params;
    const b = current.params;
    const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
    return [...keys].every((k) => a[k] === b[k]);
  }
}
