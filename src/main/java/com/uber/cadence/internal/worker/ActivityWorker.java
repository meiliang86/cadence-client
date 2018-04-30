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

package com.uber.cadence.internal.worker;

import com.uber.cadence.InternalServiceError;
import com.uber.cadence.PollForActivityTaskRequest;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.RespondActivityTaskCanceledRequest;
import com.uber.cadence.RespondActivityTaskCompletedRequest;
import com.uber.cadence.RespondActivityTaskFailedRequest;
import com.uber.cadence.ServiceBusyError;
import com.uber.cadence.TaskList;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.internal.common.Retryer;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.internal.worker.ActivityTaskHandler.Result;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ActivityWorker implements SuspendableWorker {

  private static final Logger log = LoggerFactory.getLogger(ActivityWorker.class);

  private static final String POLL_THREAD_NAME_PREFIX = "Poller taskList=";

  private Poller poller;
  private final ActivityTaskHandler handler;
  private final IWorkflowService service;
  private final String domain;
  private final String taskList;
  private final SingleWorkerOptions options;

  public ActivityWorker(
      IWorkflowService service,
      String domain,
      String taskList,
      SingleWorkerOptions options,
      ActivityTaskHandler handler) {
    Objects.requireNonNull(service);
    Objects.requireNonNull(domain);
    Objects.requireNonNull(taskList);
    this.service = service;
    this.domain = domain;
    this.taskList = taskList;
    this.options = options;
    this.handler = handler;
  }

  @Override
  public void start() {
    if (handler.isAnyTypeSupported()) {
      PollerOptions pollerOptions = options.getPollerOptions();
      if (pollerOptions.getPollThreadNamePrefix() == null) {
        pollerOptions =
            new PollerOptions.Builder(pollerOptions)
                .setPollThreadNamePrefix(
                    POLL_THREAD_NAME_PREFIX
                        + "\""
                        + taskList
                        + "\", domain=\""
                        + domain
                        + "\", type=\"activity\"")
                .build();
      }
      Poller.ThrowingRunnable pollTask =
          new PollTask<>(service, domain, taskList, options, new TaskHandlerImpl(handler));
      poller =
          new Poller(pollerOptions, options.getIdentity(), pollTask, options.getMetricsScope());
      poller.start();
      options.getMetricsScope().counter(MetricsType.WORKER_START_COUNTER).inc(1);
    }
  }

  @Override
  public void shutdown() {
    if (poller != null) {
      poller.shutdown();
    }
  }

  @Override
  public void shutdownNow() {
    if (poller != null) {
      poller.shutdownNow();
    }
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    if (poller == null) {
      return true;
    }
    return poller.awaitTermination(timeout, unit);
  }

  @Override
  public boolean shutdownAndAwaitTermination(long timeout, TimeUnit unit)
      throws InterruptedException {
    if (poller == null) {
      return true;
    }
    return poller.shutdownAndAwaitTermination(timeout, unit);
  }

  @Override
  public boolean isRunning() {
    if (poller == null) {
      return false;
    }
    return poller.isRunning();
  }

  @Override
  public void suspendPolling() {
    if (poller != null) {
      poller.suspendPolling();
    }
  }

  @Override
  public void resumePolling() {
    if (poller != null) {
      poller.resumePolling();
    }
  }

  private class MeasurableActivityTask {
    PollForActivityTaskResponse task;
    Stopwatch sw;

    MeasurableActivityTask(PollForActivityTaskResponse task, Stopwatch sw) {
      this.task = Objects.requireNonNull(task);
      this.sw = Objects.requireNonNull(sw);
    }

    void markDone() {
      sw.stop();
    }
  }

  private class TaskHandlerImpl implements PollTask.TaskHandler<MeasurableActivityTask> {

    final ActivityTaskHandler handler;

    private TaskHandlerImpl(ActivityTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(
        IWorkflowService service, String domain, String taskList, MeasurableActivityTask task)
        throws Exception {
      options
          .getMetricsScope()
          .timer(MetricsType.TASK_LIST_QUEUE_LATENCY)
          .record(
              Duration.ofNanos(
                  task.task.getStartedTimestamp() - task.task.getScheduledTimestamp()));

      try {
        Stopwatch sw = options.getMetricsScope().timer(MetricsType.ACTIVITY_EXEC_LATENCY).start();
        ActivityTaskHandler.Result response =
            handler.handle(service, domain, task.task, options.getMetricsScope());
        sw.stop();

        sw = options.getMetricsScope().timer(MetricsType.ACTIVITY_RESP_LATENCY).start();
        sendReply(task.task, response);
        sw.stop();

        task.markDone();
      } catch (CancellationException e) {
        RespondActivityTaskCanceledRequest cancelledRequest =
            new RespondActivityTaskCanceledRequest();
        cancelledRequest.setDetails(
            String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
        Stopwatch sw = options.getMetricsScope().timer(MetricsType.ACTIVITY_RESP_LATENCY).start();
        sendReply(task.task, new Result(null, null, cancelledRequest, null));
        sw.stop();
      }
    }

    @Override
    public MeasurableActivityTask poll(IWorkflowService service, String domain, String taskList)
        throws TException {
      options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_COUNTER).inc(1);
      Stopwatch sw = options.getMetricsScope().timer(MetricsType.ACTIVITY_POLL_LATENCY).start();
      Stopwatch e2eSW = options.getMetricsScope().timer(MetricsType.ACTIVITY_E2E_LATENCY).start();

      PollForActivityTaskRequest pollRequest = new PollForActivityTaskRequest();
      pollRequest.setDomain(domain);
      pollRequest.setIdentity(options.getIdentity());
      pollRequest.setTaskList(new TaskList().setName(taskList));
      if (log.isDebugEnabled()) {
        log.debug("poll request begin: " + pollRequest);
      }
      PollForActivityTaskResponse result;
      try {
        result = service.PollForActivityTask(pollRequest);
      } catch (InternalServiceError | ServiceBusyError e) {
        options
            .getMetricsScope()
            .counter(MetricsType.ACTIVITY_POLL_TRANSIENT_FAILED_COUNTER)
            .inc(1);
        throw e;
      } catch (TException e) {
        options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_FAILED_COUNTER).inc(1);
        throw e;
      }

      if (result == null || result.getTaskToken() == null) {
        if (log.isDebugEnabled()) {
          log.debug("poll request returned no task");
        }
        options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_NO_TASK_COUNTER).inc(1);
        return null;
      }

      if (log.isTraceEnabled()) {
        log.trace("poll request returned " + result);
      }

      options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_SUCCEED_COUNTER).inc(1);
      sw.stop();
      return new MeasurableActivityTask(result, e2eSW);
    }

    @Override
    public Throwable wrapFailure(MeasurableActivityTask task, Throwable failure) {
      WorkflowExecution execution = task.task.getWorkflowExecution();
      return new RuntimeException(
          "Failure processing activity task. WorkflowID="
              + execution.getWorkflowId()
              + ", RunID="
              + execution.getRunId()
              + ", ActivityType="
              + task.task.getActivityType().getName()
              + ", ActivityID="
              + task.task.getActivityId(),
          failure);
    }

    private void sendReply(PollForActivityTaskResponse task, ActivityTaskHandler.Result response)
        throws TException {
      RetryOptions ro = response.getRequestRetryOptions();
      RespondActivityTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        ro = options.getReportCompletionRetryOptions().merge(ro);
        taskCompleted.setTaskToken(task.getTaskToken());
        taskCompleted.setIdentity(options.getIdentity());
        Retryer.retry(ro, () -> service.RespondActivityTaskCompleted(taskCompleted));
        options.getMetricsScope().counter(MetricsType.ACTIVITY_TASK_COMPLETED_COUNTER).inc(1);
      } else {
        RespondActivityTaskFailedRequest taskFailed = response.getTaskFailed();
        if (taskFailed != null) {
          ro = options.getReportFailureRetryOptions().merge(ro);
          taskFailed.setTaskToken(task.getTaskToken());
          taskFailed.setIdentity(options.getIdentity());
          Retryer.retry(ro, () -> service.RespondActivityTaskFailed(taskFailed));
          options.getMetricsScope().counter(MetricsType.ACTIVITY_TASK_FAILED_COUNTER).inc(1);
        } else {
          RespondActivityTaskCanceledRequest taskCancelled = response.getTaskCancelled();
          if (taskCancelled != null) {
            taskCancelled.setTaskToken(task.getTaskToken());
            taskCancelled.setIdentity(options.getIdentity());
            ro = options.getReportFailureRetryOptions().merge(ro);
            Retryer.retry(ro, () -> service.RespondActivityTaskCanceled(taskCancelled));
            options.getMetricsScope().counter(MetricsType.ACTIVITY_TASK_CANCELED_COUNTER).inc(1);
          }
        }
      }
      // Manual activity completion
    }
  }
}
