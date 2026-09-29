ALTER TABLE tariffs
    ADD COLUMN billing_method VARCHAR(40) NOT NULL DEFAULT 'SINGLE_RATE',
    ADD COLUMN water_component_per_m3 NUMERIC(19,6),
    ADD COLUMN heat_component_per_gcal NUMERIC(19,6),
    ADD COLUMN heat_norm_gcal_per_m3 NUMERIC(19,9);

ALTER TABLE tariffs
    ADD CONSTRAINT chk_tariff_billing_method
        CHECK (billing_method IN ('SINGLE_RATE','TWO_COMPONENT_HOT_WATER')),
    ADD CONSTRAINT chk_two_component_hot_water
        CHECK (
            billing_method <> 'TWO_COMPONENT_HOT_WATER'
            OR (
                meter_type = 'HOT_WATER'
                AND water_component_per_m3 IS NOT NULL
                AND heat_component_per_gcal IS NOT NULL
                AND heat_norm_gcal_per_m3 IS NOT NULL
                AND water_component_per_m3 >= 0
                AND heat_component_per_gcal >= 0
                AND heat_norm_gcal_per_m3 >= 0
            )
        );
