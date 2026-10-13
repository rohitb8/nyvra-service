-- Date of birth, so the health score's equity glide path (FINANCIAL_RULES 4.1) can use the user's real age.
-- Nullable: users who have not given one keep the configured assumed age. Additive only.
ALTER TABLE user_profile ADD COLUMN date_of_birth DATE;
