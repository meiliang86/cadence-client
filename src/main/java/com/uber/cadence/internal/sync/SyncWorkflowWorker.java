/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.internal.sync;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.replay.ReplayDecisionTaskHandler;
import com.uber.cadence.internal.worker.DecisionTaskHandler;
import com.uber.cadence.internal.worker.SingleWorkerOptions;
import com.uber.cadence.internal.worker.WorkflowWorker;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.workflow.Functions.Func;
import com.uber.cadence.workflow.WorkflowInterceptor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Workflow worker that supports POJO workflow implementations. */
public class SyncWorkflowWorker {

  private final WorkflowWorker worker;
  private final POJOWorkflowImplementationFactory factory;
  private final SingleWorkerOptions options;

  public SyncWorkflowWorker(
      IWorkflowService service,
      String domain,
      String taskList,
      Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory,
      SingleWorkerOptions options,
      int workflowThreadPoolSize) {
    ThreadPoolExecutor workflowThreadPool =
        new ThreadPoolExecutor(
            workflowThreadPoolSize,
            workflowThreadPoolSize,
            10,
            TimeUnit.SECONDS,
            new SynchronousQueue<>());
    factory =
        new POJOWorkflowImplementationFactory(
            options.getDataConverter(), workflowThreadPool, interceptorFactory);
    DecisionTaskHandler taskHandler = new ReplayDecisionTaskHandler(domain, factory, options);
    worker = new WorkflowWorker(service, domain, taskList, options, taskHandler);
    this.options = options;
  }

  public void setWorkflowImplementationTypes(Class<?>[] workflowImplementationTypes) {
    factory.setWorkflowImplementationTypes(workflowImplementationTypes);
  }

  public <R> void addWorkflowImplementationFactory(Class<R> clazz, Func<R> factory) {
    this.factory.addWorkflowImplementationFactory(clazz, factory);
  }

  public void start() {
    worker.start();
  }

  public void shutdown() {
    worker.shutdown();
  }

  public void shutdownNow() {
    worker.shutdownNow();
  }

  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return worker.awaitTermination(timeout, unit);
  }

  public boolean shutdownAndAwaitTermination(long timeout, TimeUnit unit)
      throws InterruptedException {
    return worker.shutdownAndAwaitTermination(timeout, unit);
  }

  public boolean isRunning() {
    return worker.isRunning();
  }

  public void suspendPolling() {
    worker.suspendPolling();
  }

  public void resumePolling() {
    worker.resumePolling();
  }

  public <R> R queryWorkflowExecution(
      WorkflowExecution execution, String queryType, Class<R> returnType, Object[] args)
      throws Exception {
    DataConverter dataConverter = options.getDataConverter();
    byte[] serializedArgs = dataConverter.toData(args);
    byte[] result = worker.queryWorkflowExecution(execution, queryType, serializedArgs);
    return dataConverter.fromData(result, returnType);
  }
}
