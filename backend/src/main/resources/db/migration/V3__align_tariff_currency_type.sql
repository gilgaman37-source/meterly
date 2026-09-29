ALTER TABLE tariffs
    ALTER COLUMN currency TYPE VARCHAR(3)
    USING trim(currency);
