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

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.labs.envelope.input.BatchInput;
import com.cloudera.labs.envelope.input.Input;
import com.cloudera.labs.envelope.input.InputFactory;
import com.cloudera.labs.envelope.input.StreamInput;
import com.cloudera.labs.envelope.spark.AccumulatorRequest;
import com.cloudera.labs.envelope.spark.Accumulators;
import com.cloudera.labs.envelope.spark.Contexts;
import com.cloudera.labs.envelope.spark.Contexts.ExecutionMode;
import com.cloudera.labs.envelope.utils.StepUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

/**
 * Runner merely submits the pipeline steps to Spark in dependency order.
 * Ultimately the DAG scheduling is being coordinated by Spark, not Envelope.
 */
@SuppressWarnings("serial")
public class Runner {

  public static final String PIPELINE_THREADS_PROPERTY = "application.pipeline.threads";
  
  private static ExecutorService threadPool;
  private static Logger LOG = LoggerFactory.getLogger(Runner.class);

  /**
   * Run the Envelope pipeline
   * @param config The full configuration of the Envelope pipeline
   */
  public static void run(Config config) throws Exception {
    Set<Step> steps = extractSteps(config);
    LOG.info("Steps instantiated");

    ExecutionMode mode = StepUtils.hasStreamingStep(steps) ? Contexts.ExecutionMode.STREAMING : Contexts.ExecutionMode.BATCH;
    Contexts.initialize(config, mode);
    
    initializeAccumulators(steps);
    
    initializeUDFs(config);

    initializeThreadPool(config);

    if (StepUtils.hasStreamingStep(steps)) {
      LOG.debug("Streaming step(s) identified");

      runStreaming(steps);
    }
    else {
      LOG.debug("No streaming steps identified");

      runBatch(steps);
    }

    LOG.debug("Runner finished");
  }
  
  private static Set<Step> extractSteps(Config config) throws Exception {
    LOG.debug("Starting getting steps");

    Set<Step> steps = Sets.newHashSet();

    Set<String> stepNames = config.getObject("steps").keySet();
    for (String stepName : stepNames) {
      Config stepConfig = config.getConfig("steps").getConfig(stepName);

      Step step;

      if (!stepConfig.hasPath("type") || stepConfig.getString("type").equals("data")) {
        if (stepConfig.hasPath("input")) {
          Config stepInputConfig = stepConfig.getConfig("input");
          Input stepInput = InputFactory.create(stepInputConfig);

          if (stepInput instanceof BatchInput) {
            LOG.debug("Adding batch step: " + stepName);
            step = new BatchStep(stepName, stepConfig);
          }
          else if (stepInput instanceof StreamInput) {
            LOG.debug("Adding streaming step: " + stepName);
            step = new StreamingStep(stepName, stepConfig);
          }
          else {
            throw new RuntimeException("Invalid step input sub-class for: " + stepName);
          }
        }
        else {
          LOG.debug("Adding batch step: " + stepName);
          step = new BatchStep(stepName, stepConfig);
        }
      }
      else if (stepConfig.getString("type").equals("loop")) {
        LOG.debug("Adding loop step: " + stepName);
        step = new LoopStep(stepName, stepConfig);
      }
      else {
        throw new RuntimeException("Unknown step type: " + stepConfig.getString("type"));
      }
      
      LOG.debug("With configuration: " + stepConfig);

      steps.add(step);
    }

    LOG.debug("Finished getting steps");

    return steps;
  }

  /**
   * Run the Envelope pipeline as a Spark Streaming job.
   * @param steps The full configuration of the Envelope pipeline
   */
  private static void runStreaming(final Set<Step> steps) throws Exception {
    Set<Step> independentNonStreamingSteps = StepUtils.getIndependentNonStreamingSteps(steps);
    runBatch(independentNonStreamingSteps);

    Set<StreamingStep> streamingSteps = StepUtils.getStreamingSteps(steps);
    for (final StreamingStep streamingStep : streamingSteps) {
      LOG.debug("Setting up streaming step: " + streamingStep.getName());

      JavaDStream<Row> stream = streamingStep.getStream();

      final StructType streamSchema = streamingStep.getSchema();
      LOG.debug("Stream schema: " + streamSchema);

      stream.foreachRDD(new VoidFunction<JavaRDD<Row>>() {
        @Override
        public void call(JavaRDD<Row> batch) throws Exception {
          Dataset<Row> batchDF = Contexts.getSparkSession().createDataFrame(batch, streamSchema);
          streamingStep.setData(batchDF);
          streamingStep.setSubmitted(true);

          Set<Step> allDependentSteps = StepUtils.getAllDependentSteps(streamingStep, steps);
          runBatch(allDependentSteps);

          StepUtils.resetDataSteps(allDependentSteps);
        };
      });

      LOG.debug("Finished setting up streaming step: " + streamingStep.getName());
    }

    JavaStreamingContext jsc = Contexts.getJavaStreamingContext();
    jsc.start();
    LOG.debug("Streaming context started");
    jsc.awaitTermination();
    LOG.debug("Streaming context terminated");
  }

  /**
   * Run the steps in dependency order.
   * @param steps The steps to run, which may be the full Envelope pipeline, or a subset of it.
   */
  private static void runBatch(Set<Step> steps) throws Exception {
    LOG.debug("Started batch for steps: {}", StepUtils.stepNamesAsString(steps));

    Set<Future<Void>> offMainThreadSteps = Sets.newHashSet();
    Set<Step> refactoredSteps = null;

    // The essential logic is to loop through all of the steps until they have all been submitted.
    // Steps will not be submitted until all of their dependency steps have been submitted first.
    while (!StepUtils.allStepsSubmitted(steps)) {
      LOG.debug("Not all steps have been submitted");
      
      for (final Step step : steps) {
        LOG.debug("Looking into step: " + step.getName());

        if (step instanceof BatchStep) {
          LOG.debug("Step is batch");
          BatchStep batchStep = (BatchStep)step;

          if (!batchStep.hasSubmitted()) {
            LOG.debug("Step has not been submitted");

            final Set<Step> dependencies = StepUtils.getDependencies(step, steps);

            if (StepUtils.allStepsSubmitted(dependencies)) {
              LOG.debug("Step dependencies have finished, running step off main thread");
              // Batch steps are run off the main thread so that if they contain outputs they will
              // not block the parallel execution of independent steps.
              Future<Void> offMainThreadStep = runStepOffMainThread(batchStep, dependencies, threadPool);
              offMainThreadSteps.add(offMainThreadStep);
            }
            else {
              LOG.debug("Step dependencies have not finished");
            }
          }
          else {
            LOG.debug("Step has been submitted");
          }
        }
        else if (step instanceof StreamingStep) {
          LOG.debug("Step is streaming");
        }
        else if (step instanceof LoopStep) {
          LOG.debug("Step is a loop");
          
          LoopStep loopStep = (LoopStep)step;
          
          if (!loopStep.hasSubmitted()) {
            LOG.debug("Step has not been submitted");
          
            final Set<Step> dependencies = StepUtils.getDependencies(step, steps);
  
            if (StepUtils.allStepsSubmitted(dependencies)) {
              LOG.debug("Step dependencies have finished, unrolling loop");
              refactoredSteps = loopStep.unrollLoop(steps);
              LOG.debug("Loop unrolled");
              // We can't mutate the steps while we are iterating over them, so we break out
              // of the for-loop to then replace the steps with the loop step unrolled.
              break;
            }
            else {
              LOG.debug("Step dependencies have not been submitted");
            }
          }
          else {
            LOG.debug("Step has been submitted");
          }
        }
        else {
          throw new RuntimeException("Unknown step class type: " + step.getClass().getName());
        }

        LOG.debug("Finished looking into step: " + step.getName());
      }

      awaitAllOffMainThreadsFinished(offMainThreadSteps);
      offMainThreadSteps.clear();
      
      if (refactoredSteps != null) {
        steps = refactoredSteps;
        refactoredSteps = null;
      }
    }
    
    threadPool.shutdown();

    LOG.debug("Finished batch for steps: {}", StepUtils.stepNamesAsString(steps));
  }

  private static void initializeThreadPool(Config config) {
    if (config.hasPath(PIPELINE_THREADS_PROPERTY)) {
      threadPool = Executors.newFixedThreadPool(config.getInt(PIPELINE_THREADS_PROPERTY));
    }
    else {
      threadPool = Executors.newFixedThreadPool(20);
    }
  }

  private static Future<Void> runStepOffMainThread(final BatchStep step, final Set<Step> dependencies, final ExecutorService threadPool) {
    return threadPool.submit(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        step.submit(dependencies);
        return null;
      }
    });
  }

  private static void awaitAllOffMainThreadsFinished(Set<Future<Void>> offMainThreadSteps) throws Exception {
    for (Future<Void> offMainThreadStep : offMainThreadSteps) {
      offMainThreadStep.get();
    }
  }

  
  private static void initializeAccumulators(Set<Step> steps) {
    Set<AccumulatorRequest> requests = Sets.newHashSet();
    
    for (DataStep dataStep : StepUtils.getDataSteps(steps)) {
      requests.addAll(dataStep.getAccumulatorRequests());
    }
    
    Accumulators accumulators = new Accumulators(requests);
    
    for (DataStep dataStep : StepUtils.getDataSteps(steps)) {
      dataStep.receiveAccumulators(accumulators);
    }
  }
  
  private static void initializeUDFs(Config config) {
    if (!config.hasPath("udfs")) return;
    
    if (!config.getValue("udfs").valueType().equals(ConfigValueType.LIST)) {
      throw new RuntimeException("UDFs must be provided as a list");
    }
    
    ConfigList udfList = config.getList("udfs");
    
    for (ConfigValue udfValue : udfList) {
      ConfigValueType udfValueType = udfValue.valueType();
      if (!udfValueType.equals(ConfigValueType.OBJECT)) {
        throw new RuntimeException("UDF list must contain UDF objects");
      }
      
      Config udfConfig = ((ConfigObject)udfValue).toConfig();
      
      for (String path : Lists.newArrayList("name", "class")) {
        if (!udfConfig.hasPath(path)) {
          throw new RuntimeException("UDF entries must provide '" + path + "'");
        }
      }
      
      String name = udfConfig.getString("name");
      String className = udfConfig.getString("class");
      
      // null third argument means that registerJava will infer the return type
      Contexts.getSparkSession().udf().registerJava(name, className, null);
      
      LOG.info("Registered Spark SQL UDF: " + name);
    }
  }

}
