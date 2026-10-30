-- ----------------------------------------------------------------------------
-- What kind of business this is.
--
-- The retention engine is the same for every business: each customer is judged
-- against their own rhythm. What differs is the scale of that rhythm and the
-- words around it. A cafe regular comes weekly and a spa client every two
-- months, so a 60-day ceiling on the flag threshold that suits the cafe flags
-- the spa client before they were ever due back; and "your usual matcha" reads
-- oddly in a barbershop.
--
-- business_type picks the starting settings and the vocabulary at provisioning
-- time (BusinessType in code). It does not branch the engine, and every setting
-- it seeds remains editable afterwards.
--
-- Existing rows are cafes: that is who the product was built and demonstrated
-- for, so the default keeps their behaviour and wording exactly as it was.
-- ----------------------------------------------------------------------------
ALTER TABLE businesses
    ADD COLUMN business_type VARCHAR(24) NOT NULL DEFAULT 'CAFE_RESTAURANT'
        CHECK (business_type IN ('CAFE_RESTAURANT', 'BARBERSHOP', 'HAIR_SALON',
                                 'SPA_WELLNESS', 'OTHER'));
