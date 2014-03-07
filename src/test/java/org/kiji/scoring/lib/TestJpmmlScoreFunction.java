package org.kiji.scoring.lib;

import static org.junit.Assert.assertEquals;

import java.util.Map;

import com.google.common.collect.Maps;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.schema.EntityId;
import org.kiji.schema.KijiClientTest;
import org.kiji.schema.KijiColumnName;
import org.kiji.schema.KijiDataRequest;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiTableReader;
import org.kiji.schema.layout.KijiTableLayout;
import org.kiji.schema.layout.KijiTableLayouts;
import org.kiji.schema.shell.api.Client;
import org.kiji.schema.util.InstanceBuilder;
import org.kiji.schema.util.Resources;
import org.kiji.scoring.FreshKijiTableReader;
import org.kiji.scoring.KijiFreshnessManager;

public class TestJpmmlScoreFunction extends KijiClientTest {
  private static final Logger LOG = LoggerFactory.getLogger(TestJpmmlScoreFunction.class);

  private KijiTable mTable;
  private KijiTableReader mReader;

  @Before
  public void setupTestInternalFreshKijiTableReader() throws Exception {
    // Get the test table layouts.
    final KijiTableLayout layout = KijiTableLayout.newLayout(
        KijiTableLayouts.getLayout(KijiTableLayouts.ROW_DATA_TEST));
    final String ddlString =
        IOUtils.toString(Resources.openSystemResource("simple-linear-regression-ddl.ddl"));
    Client.newInstance(getKiji().getURI())
        .executeUpdate(ddlString);
    mTable = getKiji().openTable("linearreg");

    // Populate the environment.
    new InstanceBuilder(getKiji())
        .withTable(mTable)
            .withRow("foo").withFamily("model").withQualifier("predictor").withValue(2.0)
        .build();

    // Fill local variables.
    mReader = mTable.openTableReader();
  }

  @After
  public void cleanupTestInternalFreshKijiTableReader() throws Exception {
    mReader.close();
    mTable.release();
  }

  public void testLinearRegressionModel() throws Exception {
    final double expectedPredicted = 5.0;
    final EntityId eid = mTable.getEntityId("foo");
    final KijiDataRequest request = KijiDataRequest.create("model", "predicted");

    // Setup parameters for score function.
    final Map<String, KijiColumnName> predictors = Maps.newHashMap();
    predictors.put("predictor", new KijiColumnName("model", "predictor"));
    final Map<String, String> predicted = Maps.newHashMap();
    predicted.put("predicted", "predicted");
    final Map<String, String> parameters = JpmmlScoreFunction.parameters(
        // TODO: Write out the pmml file somewhere and read this in (possibly revisit the way this
        // is passed in).
        "",
        "linearreg",
        "SimpleLinearRegressionPredicted",
        predictors,
        predicted
    );

    // Create a KijiFreshnessManager and register some Fresheners.
    final KijiFreshnessManager manager = KijiFreshnessManager.create(getKiji());
    try {
      manager.registerFreshener(
          "linearreg",
          new KijiColumnName("model", "predictor"),
          new AlwaysFreshen(),
          new JpmmlScoreFunction(),
          parameters,
          false,
          false
      );
    } finally {
      manager.close();
    }
    // Open a new reader to pull in the new freshness policies. Allow 10 seconds so it is very
    // unlikely to timeout.
    final FreshKijiTableReader freshReader = FreshKijiTableReader.Builder.create()
        .withTable(mTable)
        .withTimeout(10000)
        .build();
    try {
      final GenericRecord result =
          freshReader.get(eid, request).getMostRecentValue("model", "predicted");
      // Ensure score is correct.
      assertEquals(
          5.0,
          result.get("predicted")
      );
    } finally {
      freshReader.close();
    }
  }
}
