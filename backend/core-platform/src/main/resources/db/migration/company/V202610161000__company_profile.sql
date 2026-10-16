-- Optional company profile: website, address, GST/PAN, logo and favicon (object keys in the file bucket),
-- plus a SUPPORT contact type next to PRIMARY, BILLING and TECHNICAL.
ALTER TABLE company
    ADD COLUMN website        VARCHAR(255) NULL,
    ADD COLUMN address_line1  VARCHAR(200) NULL,
    ADD COLUMN address_line2  VARCHAR(200) NULL,
    ADD COLUMN city           VARCHAR(100) NULL,
    ADD COLUMN state          VARCHAR(100) NULL,
    ADD COLUMN postal_code    VARCHAR(20)  NULL,
    ADD COLUMN gst_number     VARCHAR(15)  NULL,
    ADD COLUMN pan_number     VARCHAR(10)  NULL,
    ADD COLUMN logo_key       VARCHAR(300) NULL,
    ADD COLUMN favicon_key    VARCHAR(300) NULL;

ALTER TABLE company_contact DROP CHECK ck_company_contact_type;
ALTER TABLE company_contact ADD CONSTRAINT ck_company_contact_type
    CHECK (type IN ('PRIMARY', 'BILLING', 'TECHNICAL', 'SUPPORT'));
