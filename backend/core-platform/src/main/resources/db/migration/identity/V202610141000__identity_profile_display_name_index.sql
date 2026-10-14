-- Member search by name prefix starts from matching profiles (load test H-3: search in a company with
-- 50,000 members went from ~200 ms to ~4 ms). Online DDL; no table rebuild needed for a secondary index.
CREATE INDEX ix_user_profile_display_name ON user_profile (display_name);
