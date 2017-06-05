/**
 * Copyright © 2016-2017 Cloudera, Inc.
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
package com.cloudera.labs.envelope.run;

import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.streaming.api.java.JavaDStream;

import com.cloudera.labs.envelope.input.StreamInput;
import com.typesafe.config.Config;

/**
 * A streaming step is a data step that provides a DataFrame per Spark Streaming micro-batch.
 */
public class StreamingStep extends DataStep {

  public static final String REPARTITION_NUM_PARTITIONS_PROPERTY = "input.repartition.partitions";

  public StreamingStep(String name, Config config) {
    super(name, config);
  }

  public JavaDStream<Row> getStream() throws Exception {
    JavaDStream<Row> stream = ((StreamInput)getInput()).getDStream();

    if (doesRepartition()) {
      stream = repartition(stream);
    }

    return stream;
  }

  public StructType getSchema() throws Exception {
    StructType schema = ((StreamInput)getInput()).getSchema();

    return schema;
  }

  private boolean doesRepartition() {
    return config.hasPath(REPARTITION_NUM_PARTITIONS_PROPERTY);
  }

  private <T> JavaDStream<T> repartition(JavaDStream<T> stream) {
    int numPartitions = config.getInt(REPARTITION_NUM_PARTITIONS_PROPERTY);

    return stream.repartition(numPartitions);
  }

  @Override
  public Step copy() {
    StreamingStep copy = new StreamingStep(name, config);
    
    copy.setSubmitted(hasSubmitted());
    
    if (hasSubmitted()) {
      copy.setData(getData());
    }
    
    return copy;
  }

}
