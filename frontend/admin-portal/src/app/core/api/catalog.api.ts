import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrlBuilder, ifMatch, query } from './api-url';
import {
  NewFeature,
  Page,
  Product,
  ProductData,
  ProductFeature,
  ProductStatus,
  ProductVersion,
} from './models';

@Injectable({ providedIn: 'root' })
export class CatalogApi {
  private readonly http = inject(HttpClient);
  private readonly api = apiUrlBuilder();

  search(
    criteria: {
      status?: ProductStatus | null;
      q?: string | null;
      page?: number;
      size?: number;
    } = {},
  ): Observable<Page<Product>> {
    return this.http.get<Page<Product>>(this.api('/products'), { params: query({ ...criteria }) });
  }

  get(id: string): Observable<Product> {
    return this.http.get<Product>(this.api('/products', id));
  }

  register(product: ProductData): Observable<Product> {
    return this.http.post<Product>(this.api('/products'), product);
  }

  update(product: Product, changes: ProductData): Observable<Product> {
    return this.http.patch<Product>(this.api('/products', product.id), changes, {
      headers: ifMatch(product.version),
    });
  }

  changeStatus(
    id: string,
    action: 'activate' | 'retire',
    reason: string | null,
  ): Observable<Product> {
    return this.http.post<Product>(this.api('/products', id, '/' + action), { reason });
  }

  versions(id: string): Observable<ProductVersion[]> {
    return this.http.get<ProductVersion[]>(this.api('/products', id, '/versions'));
  }

  version(id: string, versionNo: number): Observable<ProductVersion> {
    return this.http.get<ProductVersion>(this.api('/products', id, '/versions', String(versionNo)));
  }

  createVersion(id: string, config: unknown, notes: string | null): Observable<ProductVersion> {
    return this.http.post<ProductVersion>(this.api('/products', id, '/versions'), {
      config,
      notes,
    });
  }

  publishVersion(id: string, versionNo: number): Observable<ProductVersion> {
    return this.http.post<ProductVersion>(
      this.api('/products', id, '/versions', String(versionNo), '/publish'),
      {},
    );
  }

  deleteVersion(id: string, versionNo: number): Observable<void> {
    return this.http.delete<void>(this.api('/products', id, '/versions', String(versionNo)));
  }

  features(id: string): Observable<ProductFeature[]> {
    return this.http.get<ProductFeature[]>(this.api('/products', id, '/features'));
  }

  addFeature(id: string, feature: NewFeature): Observable<ProductFeature> {
    return this.http.post<ProductFeature>(this.api('/products', id, '/features'), feature);
  }

  changeFeatureStatus(
    id: string,
    featureId: string,
    action: 'deprecate' | 'reactivate',
  ): Observable<ProductFeature> {
    return this.http.post<ProductFeature>(
      this.api('/products', id, '/features', featureId, '/' + action),
      {},
    );
  }
}
