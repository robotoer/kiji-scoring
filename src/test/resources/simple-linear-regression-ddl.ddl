CREATE TABLE linearreg WITH DESCRIPTION 'A table for scoring a simple linear regression model.'
  ROW KEY FORMAT HASH PREFIXED(1)
  WITH LOCALITY GROUP default WITH DESCRIPTION 'Main locality group.' (
    MAXVERSIONS = 1,
    TTL = FOREVER,
    INMEMORY = false,
    COMPRESSED WITH NONE,
    FAMILY model with DESCRIPTION 'Data related to scoring a simple linear regression model.' (
      predictor "double" WITH DESCRIPTION 'The predictor field (x in y = ax + b).',
      predicted WITH SCHEMA {
        "type": "record",
        "name": "SimpleLinearRegressionPredicted",
        "fields": [
          { "name": "predicted", "type": "double" }
        ]
      }
    )
  );
