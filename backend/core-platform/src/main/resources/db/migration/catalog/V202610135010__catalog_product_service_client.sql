-- Phase 7: the Keycloak client a product service uses to call Core's service API. A service token
-- is accepted only for the product whose service_client_id matches its client_id claim.
ALTER TABLE product
    ADD COLUMN service_client_id VARCHAR(100) NULL AFTER api_audience,
    ADD CONSTRAINT uk_product_service_client UNIQUE (service_client_id);
