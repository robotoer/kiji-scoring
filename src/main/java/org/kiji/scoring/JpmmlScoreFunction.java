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
package org.kiji.scoring;

import java.io.File;
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
import org.dmg.pmml.FieldName;
import org.dmg.pmml.IOUtil;
import org.dmg.pmml.PMML;
import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.ModelEvaluatorFactory;
import org.jpmml.manager.PMMLManager;
import org.xml.sax.SAXException;

import org.kiji.schema.*;

public class JpmmlScoreFunction extends ScoreFunction {
  private Evaluator mEvaluator;
  private Schema mEvaluatorSchema;

  static public Evaluator loadEvaluator(String modelFile, String modelName) {
    PMML pmml = null;
    try {
      pmml = IOUtil.unmarshal(new File(modelFile));
    } catch (IOException e) {
      e.printStackTrace();
    } catch (SAXException e) {
      e.printStackTrace();
    } catch (JAXBException e) {
      e.printStackTrace();
    }

    final PMMLManager pmmlManager = new PMMLManager(pmml);

    // Load the default model
    return (Evaluator) pmmlManager.getModelManager(modelName, ModelEvaluatorFactory.getInstance());
  }

  static public KijiColumnName fieldNameToColumnName(
      FieldName fieldName,
      FreshenerContext context
  ) {
    return new KijiColumnName(context.getParameter(fieldName.getValue()));
  }

  static public Schema schemaForField(
      FieldName fieldName,
      FreshenerSetupContext context
  ) {
    final Schema.Parser parser = new Schema.Parser();
    return parser.parse(context.getParameter(fieldName.getValue()));
  }

  public Schema evaluatorSchema(
      Evaluator evaluator,
      FreshenerSetupContext context
  ) {
    List<Schema.Field> fields = Lists.newArrayList();
    for (FieldName field : evaluator.getPredictedFields()) {
      final Schema.Field schemaField = new Schema.Field(
          field.getValue(),
          schemaForField(field, context),
          "",
          null
      );
      fields.add(schemaField);
    }
    for (FieldName field : evaluator.getOutputFields()) {
      final Schema.Field schemaField = new Schema.Field(
          field.getValue(),
          schemaForField(field, context),
          "",
          null
      );
      fields.add(schemaField);
    }
    return Schema.createRecord(fields);
  }

  @Override
  public void setup(FreshenerSetupContext context) throws IOException {
    final Map<String, String> parameters = context.getParameters();
    Preconditions.checkArgument(parameters.containsKey("model-file"));
    Preconditions.checkArgument(parameters.containsKey("model-name"));
    mEvaluator = loadEvaluator(
        context.getParameter("model-file"),
        context.getParameter("model-name")
    );
    mEvaluatorSchema = evaluatorSchema(mEvaluator, context);

    super.setup(context);
  }

  @Override
  public KijiDataRequest getDataRequest(FreshenerContext context) throws IOException {
    // Expects that there is a parameter for each.
    final KijiDataRequestBuilder builder = KijiDataRequest.builder();
    for (FieldName field : mEvaluator.getActiveFields()) {
      builder.addColumns(builder.newColumnsDef().add(fieldNameToColumnName(field, context)));
    }
    return builder.build();
  }

  @Override
  public TimestampedValue score(KijiRowData dataToScore, FreshenerContext context)
      throws IOException {
    final Map<FieldName, Object> arguments = Maps.newHashMap();
    for (FieldName field : mEvaluator.getActiveFields()) {
      final KijiColumnName columnName = fieldNameToColumnName(field, context);
      final Object argument = mEvaluator.prepare(
          field,
          dataToScore.getMostRecentValue(columnName.getFamily(), columnName.getQualifier())
      );
      arguments.put(field, argument);
    }

    // Pack this into a record and write it to the column.
    final Map<FieldName, ?> predicted = mEvaluator.evaluate(arguments);
    final GenericRecordBuilder recordBuilder = new GenericRecordBuilder(mEvaluatorSchema);
    for (Map.Entry<FieldName, ?> entry : predicted.entrySet()) {
      recordBuilder.set(entry.getKey().getValue(), entry.getValue());
    }
    return TimestampedValue.<GenericRecord>create(recordBuilder.build());
  }
}
