/**
 * (c) Copyright 2014 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kiji.scoring.lib;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.xml.bind.JAXBException;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.dmg.pmml.DataDictionary;
import org.dmg.pmml.DataField;
import org.dmg.pmml.DataType;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.IOUtil;
import org.dmg.pmml.PMML;
import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.FieldValue;
import org.jpmml.evaluator.ModelEvaluatorFactory;
import org.jpmml.manager.PMMLManager;
import org.xml.sax.SAXException;

import org.kiji.schema.KijiColumnName;
import org.kiji.schema.KijiDataRequest;
import org.kiji.schema.KijiRowData;
import org.kiji.scoring.FreshenerContext;
import org.kiji.scoring.FreshenerSetupContext;
import org.kiji.scoring.ScoreFunction;

/**
 * A generic score function for scoring already-trained PMML-compliant (and supported by Jpmml)
 * models.
 *
 * Expected parameters:
 * <ul>
 *   <li>
 *     "org.kiji.scoring.lib.JpmmlScoreFunction.model-file" - The path to the trained model file.
 *   </li>
 *   <li>
 *     "org.kiji.scoring.lib.JpmmlScoreFunction.model-name" - The name of the model to load.
 *   </li>
 *   <li>
 *     "org.kiji.scoring.lib.JpmmlScoreFunction.predictor-column" - The name of the column
 *     containing the model's predictors.
 *   </li>
 *   <li>
 *     "org.kiji.scoring.lib.JpmmlScoreFunction.result-record-name" - The name of the record that
 *     will contain the predicted and output records.
 *   </li>
 * </ul>
 *
 * Note: Extensions (http://www.dmg.org/v4-2/GeneralStructure.html#xsdElement_Extension) are
 *     currently <b>NOT SUPPORTED</b>.
 */
public class JpmmlScoreFunction extends ScoreFunction<GenericRecord> {
  /** Parameter name for specifying the path to the trained model file. */
  public static final String MODEL_FILE_PARAMETER =
      "org.kiji.scoring.lib.JpmmlScoreFunction.model-file";
  /** Parameter name for specifying the name of the trained model. */
  public static final String MODEL_NAME_PARAMETER =
      "org.kiji.scoring.lib.JpmmlScoreFunction.model-name";
  /** Parameter name for specifying the name of the predictor column. */
  public static final String PREDICTOR_COLUMN_PARAMETER =
      "org.kiji.scoring.lib.JpmmlScoreFunction.predictor-column";
  /** Parameter name for specifying the name of the result record. */
  public static final String RESULT_RECORD_PARAMETER =
      "org.kiji.scoring.lib.JpmmlScoreFunction.result-record-name";
  /** Parameter name for specifying the types of the result record fields. */
  public static final String RESULT_TYPES_PARAMETER =
      "org.kiji.scoring.lib.JpmmlScoreFunction.result-field-types";

  /** Stores the evaluator for the provided model. */
  private Evaluator mEvaluator = null;
  /** Stores the schema for the scores calculated by the provided model. */
  private Schema mResultSchema = null;

  /**
   * Checks to ensure required parameters have been provided, loads the Jpmml evaluator, and creates
   * the result record schema.
   *
   * {@inheritDoc}
   */
  @SuppressWarnings("unchecked")
  @Override
  public void setup(
      final FreshenerSetupContext context
  ) throws IOException {
    super.setup(context);

    final Configuration configuration = HBaseConfiguration.create();

    // Ensure all parameters are specified correctly.
    final Map<String, String> parameters = context.getParameters();
    Preconditions.checkArgument(
        parameters.containsKey(MODEL_FILE_PARAMETER),
        String.format("Missing required parameter: %s", MODEL_FILE_PARAMETER)
    );
    Preconditions.checkArgument(
        parameters.containsKey(MODEL_NAME_PARAMETER),
        String.format("Missing required parameter: %s", MODEL_NAME_PARAMETER)
    );
    Preconditions.checkArgument(
        parameters.containsKey(PREDICTOR_COLUMN_PARAMETER),
        String.format("Missing required parameter: %s", PREDICTOR_COLUMN_PARAMETER)
    );
    Preconditions.checkArgument(
        parameters.containsKey(RESULT_RECORD_PARAMETER),
        String.format("Missing required parameter: %s", RESULT_RECORD_PARAMETER)
    );
    Preconditions.checkArgument(
        parameters.containsKey(RESULT_TYPES_PARAMETER),
        String.format("Missing required parameter: %s", RESULT_TYPES_PARAMETER)
    );

    // Parse parameters.
    final Path modelFilePath = new Path(parameters.get(MODEL_FILE_PARAMETER));
    final String modelName = parameters.get(MODEL_NAME_PARAMETER);
    final String resultRecordName = parameters.get(RESULT_RECORD_PARAMETER);

    // Load the PMML model.
    PMML pmml;
    try {
      final FileSystem fileSystem = modelFilePath.getFileSystem(configuration);
      try {
        final FSDataInputStream fsDataInputStream = fileSystem.open(modelFilePath);
        try {
          pmml = IOUtil.unmarshal(fsDataInputStream);
        } finally {
          fsDataInputStream.close();
        }
      } finally {
        fileSystem.close();
      }
    } catch (JAXBException e) {
      throw new IOException(e);
    } catch (SAXException e) {
      throw new IOException(e);
    }
    final PMMLManager pmmlManager = new PMMLManager(pmml);

    // Load the default model
    mEvaluator = (Evaluator) pmmlManager.getModelManager(
        modelName,
        ModelEvaluatorFactory.getInstance()
    );

    // Build required schemas.
    final DataDictionary dataDictionary = pmmlManager.getDataDictionary();
    final Map<FieldName, Schema> fieldSchemas = Maps.newHashMap();
    for (DataField dataField : dataDictionary.getDataFields()) {
      fieldSchemas.put(dataField.getName(), dataTypeToSchema(dataField.getDataType()));
    }

    final List<FieldName> resultFields = Lists.newArrayList();
    resultFields.addAll(mEvaluator.getPredictedFields());
    resultFields.addAll(mEvaluator.getOutputFields());
    mResultSchema = fieldNamesToSchema(resultRecordName, resultFields, fieldSchemas);
  }

  /**
   * Requests the provided predictor column.
   *
   * {@inheritDoc}
   */
  @Override
  public KijiDataRequest getDataRequest(
      final FreshenerContext context
  ) throws IOException {
    final KijiColumnName predictorColumnName =
        new KijiColumnName(context.getParameter(PREDICTOR_COLUMN_PARAMETER));
    return KijiDataRequest.create(
        predictorColumnName.getFamily(),
        predictorColumnName.getQualifier()
    );
  }

  /**
   * Generates a score using Jpmml's evaluators.
   *
   * {@inheritDoc}
   */
  @Override
  public TimestampedValue<GenericRecord> score(
      final KijiRowData dataToScore,
      final FreshenerContext context
  ) throws IOException {
    // Load appropriate arguments.
    final KijiColumnName predictorColumnName =
        new KijiColumnName(context.getParameter(PREDICTOR_COLUMN_PARAMETER));

    final GenericRecord predictors = dataToScore.getMostRecentValue(
        predictorColumnName.getFamily(),
        predictorColumnName.getQualifier()
    );

    // Build the arguments to the pmml model evaluator.
    final Map<FieldName, Object> arguments = Maps.newHashMap();
    for (FieldName field : mEvaluator.getActiveFields()) {
      final Object argument = mEvaluator.prepare(field, predictors.get(field.getValue()));
      arguments.put(field, argument);
    }

    // Calculate the scores.
    final Map<FieldName, ?> results = mEvaluator.evaluate(arguments);

    // Pack this into a record and write it to the column.
    final GenericRecordBuilder resultRecordBuilder = new GenericRecordBuilder(mResultSchema);
    for (Map.Entry<FieldName, ?> entry : results.entrySet()) {
      final FieldName fieldName = entry.getKey();
      mEvaluator.getDataField(fieldName).getDataType();
      resultRecordBuilder.set(entry.getKey().getValue(), entry.getValue());
    }
    return TimestampedValue.<GenericRecord>create(resultRecordBuilder.build());
  }

  /**
   * Converts a PMML data type into an Avro schema.
   *
   * @param dataType to convert.
   * @return an appropriate Avro schema.
   */
  public static Schema dataTypeToSchema(
      final DataType dataType
  ) {
    switch (dataType) {
      case STRING:
        return Schema.create(Schema.Type.STRING);
      case INTEGER:
        return Schema.create(Schema.Type.LONG);
      case FLOAT:
        return Schema.create(Schema.Type.FLOAT);
      case DOUBLE:
        return Schema.create(Schema.Type.DOUBLE);
      case BOOLEAN:
        return Schema.create(Schema.Type.BOOLEAN);
      case DATE:
        return Schema.create(Schema.Type.STRING);
      case TIME:
        return Schema.create(Schema.Type.STRING);
      case DATE_TIME:
        return Schema.create(Schema.Type.STRING);
      case DATE_DAYS_SINCE_0:
        return Schema.create(Schema.Type.LONG);
      case DATE_DAYS_SINCE_1960:
        return Schema.create(Schema.Type.LONG);
      case DATE_DAYS_SINCE_1970:
        return Schema.create(Schema.Type.LONG);
      case DATE_DAYS_SINCE_1980:
        return Schema.create(Schema.Type.LONG);
      case TIME_SECONDS:
        return Schema.create(Schema.Type.LONG);
      case DATE_TIME_SECONDS_SINCE_0:
        return Schema.create(Schema.Type.LONG);
      case DATE_TIME_SECONDS_SINCE_1960:
        return Schema.create(Schema.Type.LONG);
      case DATE_TIME_SECONDS_SINCE_1970:
        return Schema.create(Schema.Type.LONG);
      case DATE_TIME_SECONDS_SINCE_1980:
        return Schema.create(Schema.Type.LONG);
      default:
        throw new IllegalArgumentException(
            String.format("Unsupported DataType: %s", dataType.value())
        );
    }
  }

  public static <T> T fieldValueToAvro(
      final Object data,
      final DataType dataType
  ) {
    switch (dataType) {
      case STRING:
        break;
      case INTEGER:
        break;
      case FLOAT:
        break;
      case DOUBLE:
        break;
      case BOOLEAN:
        break;
      case DATE:
        break;
      case TIME:
        break;
      case DATE_TIME:
        break;
      case DATE_DAYS_SINCE_0:
        break;
      case DATE_DAYS_SINCE_1960:
        break;
      case DATE_DAYS_SINCE_1970:
        break;
      case DATE_DAYS_SINCE_1980:
        break;
      case TIME_SECONDS:
        break;
      case DATE_TIME_SECONDS_SINCE_0:
        break;
      case DATE_TIME_SECONDS_SINCE_1960:
        break;
      case DATE_TIME_SECONDS_SINCE_1970:
        break;
      case DATE_TIME_SECONDS_SINCE_1980:
        break;
      default:
        throw new IllegalArgumentException(
            String.format("Unsupported DataType: %s", dataType.value())
        );
    }
  }

  /**
   * Builds a schema for the provided PMML fields. Fields in the resulting name will match exactly
   * the provided PMML field names.
   *
   * @param recordName of the desired record schema.
   * @param fieldNames of the desired record schema.
   * @param fieldTypes of the desired record schema.
   * @return a record schema for the provided PMML fields.
   */
  public static Schema fieldNamesToSchema(
      final String recordName,
      final Iterable<FieldName> fieldNames,
      final Map<FieldName, Schema> fieldTypes
  ) {
    final List<Schema.Field> fields = Lists.newArrayList();
    for (FieldName field : fieldNames) {
      Preconditions.checkArgument(
          fieldTypes.containsKey(field),
          String.format("Missing type for field: %s", field.getValue())
      );
      final Schema.Field schemaField = new Schema.Field(
          field.getValue(),
          fieldTypes.get(field),
          null,
          null
      );
      fields.add(schemaField);
    }
    final Schema predictedRecord =
        Schema.createRecord(recordName, null, null, false);
    predictedRecord.setFields(fields);
    return predictedRecord;
  }

  /**
   * Builds the appropriate parameters for this score function.
   *
   * @param modelFile containing the trained PMML model.
   * @param modelName of the trained PMML model.
   * @param predictorColumn that the trained PMML model requires to generate a score.
   * @param resultRecordName of the output record to be stored from the trained PMML model.
   * @return the parameters to be used by this score function.
   */
  public static Map<String, String> parameters(
      final String modelFile,
      final String modelName,
      final KijiColumnName predictorColumn,
      final String resultRecordName
  ) {
    final Map<String, String> parameters = Maps.newHashMap();
    parameters.put(MODEL_FILE_PARAMETER, modelFile);
    parameters.put(MODEL_NAME_PARAMETER, modelName);
    parameters.put(PREDICTOR_COLUMN_PARAMETER, predictorColumn.getName());
    parameters.put(RESULT_RECORD_PARAMETER, resultRecordName);

    return parameters;
  }
}
