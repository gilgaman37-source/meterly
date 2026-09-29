DELETE FROM tariffs
WHERE price_per_unit = 0
  AND source_url = 'https://example.invalid/replace-with-official-tariff';
