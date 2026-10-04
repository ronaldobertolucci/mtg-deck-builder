ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT false;

-- Preserve access for previously enabled accounts. A used verification token is
-- evidence of confirmation for disabled accounts, but never evidence that an
-- administrative restriction can be removed. Never change enabled here.
UPDATE users
SET email_verified = true
WHERE enabled = true
   OR EXISTS (SELECT 1 FROM email_verification_tokens t WHERE t.user_id = users.id AND t.used = true);
