-- Monotonic cancellation is independent of provider outcome and callback occupancy.
-- Existing requests retain their state and remain uncancelled.
ALTER TABLE campaign_public_request ADD COLUMN cancelled_at BIGINT NULL;
