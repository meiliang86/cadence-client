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

import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.GetWorkflowExecutionHistoryResponse;
import com.uber.cadence.History;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.InternalServiceError;
import com.uber.cadence.PollForDecisionTaskRequest;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.RespondDecisionTaskCompletedRequest;
import com.uber.cadence.RespondDecisionTaskFailedRequest;
import com.uber.cadence.RespondQueryTaskCompletedRequest;
import com.uber.cadence.ServiceBusyError;
import com.uber.cadence.TaskList;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.internal.common.Retryer;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.m3.tally.Stopwatch;
import java.time.Duration;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorkflowWorker implements SuspendableWorker {

  private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);

  private static final String POLL_THREAD_NAME_PREFIX = "Poller taskList=";
  private static final int MAXIMUM_PAGE_SIZE = 10000;

  private Poller poller;
  private final DecisionTaskHandler handler;
  private final IWorkflowService service;
  private final String domain;
  private final String taskList;
  private final SingleWorkerOptions options;

  public WorkflowWorker(
      IWorkflowService service,
      String domain,
      String taskList,
      SingleWorkerOptions options,
      DecisionTaskHandler handler) {
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
                        + "\", type=\"workflow\"")
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

  public byte[] queryWorkflowExecution(WorkflowExecution execution, String queryType, byte[] args)
      throws Exception {
    Iterator<HistoryEvent> history = WorkflowExecutionUtils.getHistory(service, domain, execution);
    DecisionTaskWithHistoryIterator historyIterator =
        new ReplayDecisionTaskWithHistoryIterator(execution, history);
    WorkflowQuery query = new WorkflowQuery();
    query.setQueryType(queryType).setQueryArgs(args);
    historyIterator.getDecisionTask().setQuery(query);
    DecisionTaskHandler.Result result = handler.handleDecisionTask(historyIterator);
    if (result.getQueryCompleted() != null) {
      RespondQueryTaskCompletedRequest r = result.getQueryCompleted();
      return r.getQueryResult();
    }
    throw new RuntimeException("Query returned wrong response: " + result);
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

  private class TaskHandlerImpl implements PollTask.TaskHandler<PollForDecisionTaskResponse> {

    final DecisionTaskHandler handler;

    private TaskHandlerImpl(DecisionTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(
        IWorkflowService service, String domain, String taskList, PollForDecisionTaskResponse task)
        throws Exception {
      Stopwatch sw =
          options.getMetricsScope().timer(MetricsType.DECISION_EXECUTION_LATENCY).start();
      DecisionTaskHandler.Result response =
          handler.handleDecisionTask(new DecisionTaskWithHistoryIteratorImpl(task));
      sw.stop();

      sw = options.getMetricsScope().timer(MetricsType.DECISION_RESPONSE_LATENCY).start();
      sendReply(service, task.getTaskToken(), response);
      sw.stop();

      options.getMetricsScope().counter(MetricsType.DECISION_TASK_COMPLETED_COUNTER).inc(1);
    }

    @Override
    public PollForDecisionTaskResponse poll(
        IWorkflowService service, String domain, String taskList) throws TException {
      options.getMetricsScope().counter(MetricsType.DECISION_POLL_COUNTER).inc(1);
      Stopwatch sw = options.getMetricsScope().timer(MetricsType.DECISION_POLL_LATENCY).start();

      PollForDecisionTaskRequest pollRequest = new PollForDecisionTaskRequest();
      pollRequest.setDomain(domain);
      pollRequest.setIdentity(options.getIdentity());

      TaskList tl = new TaskList();
      tl.setName(taskList);
      pollRequest.setTaskList(tl);

      if (log.isDebugEnabled()) {
        log.debug("poll request begin: " + pollRequest);
      }
      PollForDecisionTaskResponse result;
      try {
        result = service.PollForDecisionTask(pollRequest);
      } catch (InternalServiceError | ServiceBusyError e) {
        options
            .getMetricsScope()
            .counter(MetricsType.DECISION_POLL_TRANSIENT_FAILED_COUNTER)
            .inc(1);
        throw e;
      } catch (TException e) {
        options.getMetricsScope().counter(MetricsType.DECISION_POLL_FAILED_COUNTER).inc(1);
        throw e;
      }

      if (log.isDebugEnabled()) {
        log.debug(
            "poll request returned decision task: workflowType="
                + result.getWorkflowType()
                + ", workflowExecution="
                + result.getWorkflowExecution()
                + ", startedEventId="
                + result.getStartedEventId()
                + ", previousStartedEventId="
                + result.getPreviousStartedEventId()
                + (result.getQuery() != null
                    ? ", queryType=" + result.getQuery().getQueryType()
                    : ""));
      }

      if (result == null || result.getTaskToken() == null) {
        options.getMetricsScope().counter(MetricsType.DECISION_POLL_NO_TASK_COUNTER).inc(1);
        return null;
      }

      options.getMetricsScope().counter(MetricsType.DECISION_POLL_SUCCEED_COUNTER).inc(1);
      sw.stop();
      return result;
    }

    @Override
    public Throwable wrapFailure(PollForDecisionTaskResponse task, Throwable failure) {
      WorkflowExecution execution = task.getWorkflowExecution();
      return new RuntimeException(
          "Failure processing decision task. WorkflowID="
              + execution.getWorkflowId()
              + ", RunID="
              + execution.getRunId(),
          failure);
    }

    private void sendReply(
        IWorkflowService service, byte[] taskToken, DecisionTaskHandler.Result response)
        throws TException {
      RetryOptions ro = response.getRequestRetryOptions();
      RespondDecisionTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        ro = options.getReportCompletionRetryOptions().merge(ro);
        taskCompleted.setIdentity(options.getIdentity());
        taskCompleted.setTaskToken(taskToken);
        Retryer.retry(ro, () -> service.RespondDecisionTaskCompleted(taskCompleted));
      } else {
        RespondDecisionTaskFailedRequest taskFailed = response.getTaskFailed();
        if (taskFailed != null) {
          ro = options.getReportFailureRetryOptions().merge(ro);
          taskFailed.setIdentity(options.getIdentity());
          taskFailed.setTaskToken(taskToken);
          Retryer.retry(ro, () -> service.RespondDecisionTaskFailed(taskFailed));
        } else {
          RespondQueryTaskCompletedRequest queryCompleted = response.getQueryCompleted();
          if (queryCompleted != null) {
            queryCompleted.setTaskToken(taskToken);
            // Do not retry query response.
            service.RespondQueryTaskCompleted(queryCompleted);
          }
        }
      }
      // Manual activity completion
    }
  }

  private class DecisionTaskWithHistoryIteratorImpl implements DecisionTaskWithHistoryIterator {

    private long start = System.currentTimeMillis();
    private final PollForDecisionTaskResponse task;
    private Iterator<HistoryEvent> current;
    private byte[] nextPageToken;
    private WorkflowExecutionStartedEventAttributes startedEvent;

    DecisionTaskWithHistoryIteratorImpl(PollForDecisionTaskResponse task) {
      this.task = task;
      History history = task.getHistory();
      HistoryEvent firstEvent = history.getEvents().get(0);
      this.startedEvent = firstEvent.getWorkflowExecutionStartedEventAttributes();
      if (this.startedEvent == null) {
        throw new IllegalArgumentException(
            "First event in the history is not WorkflowExecutionStarted");
      }
      current = history.getEventsIterator();
      nextPageToken = task.getNextPageToken();
    }

    @Override
    public PollForDecisionTaskResponse getDecisionTask() {
      return task;
    }

    @Override
    public Iterator<HistoryEvent> getHistory() {
      return new Iterator<HistoryEvent>() {
        @Override
        public boolean hasNext() {
          return current.hasNext() || nextPageToken != null;
        }

        @Override
        public HistoryEvent next() {
          if (current.hasNext()) {
            return current.next();
          }
          Duration passed = Duration.ofMillis(System.currentTimeMillis() - start);
          Duration timeout = Duration.ofSeconds(startedEvent.getTaskStartToCloseTimeoutSeconds());
          Duration expiration = timeout.minus(passed);
          if (expiration.isZero() || expiration.isNegative()) {
            throw new Error("History pagination time exceeded TaskStartToCloseTimeout");
          }

          options.getMetricsScope().counter(MetricsType.WORKFLOW_GET_HISTORY_COUNTER).inc(1);
          Stopwatch sw =
              options.getMetricsScope().timer(MetricsType.WORKFLOW_GET_HISTORY_LATENCY).start();
          RetryOptions retryOptions =
              new RetryOptions.Builder()
                  .setExpiration(expiration)
                  .setInitialInterval(Duration.ofMillis(50))
                  .setMaximumInterval(Duration.ofSeconds(1))
                  .build();

          GetWorkflowExecutionHistoryRequest request = new GetWorkflowExecutionHistoryRequest();
          request.setDomain(domain);
          request.setExecution(task.getWorkflowExecution());
          request.setMaximumPageSize(MAXIMUM_PAGE_SIZE);
          try {
            GetWorkflowExecutionHistoryResponse r =
                Retryer.retryWithResult(
                    retryOptions, () -> service.GetWorkflowExecutionHistory(request));
            current = r.getHistory().getEventsIterator();
            nextPageToken = r.getNextPageToken();
            options
                .getMetricsScope()
                .counter(MetricsType.WORKFLOW_GET_HISTORY_SUCCEED_COUNTER)
                .inc(1);
            sw.stop();
          } catch (TException e) {
            options
                .getMetricsScope()
                .counter(MetricsType.WORKFLOW_GET_HISTORY_FAILED_COUNTER)
                .inc(1);
            throw new Error(e);
          }
          return current.next();
        }
      };
    }

    @Override
    public WorkflowExecutionStartedEventAttributes getStartedEvent() {
      return startedEvent;
    }
  }

  private static class ReplayDecisionTaskWithHistoryIterator
      implements DecisionTaskWithHistoryIterator {

    private final Iterator<HistoryEvent> history;
    private final PollForDecisionTaskResponse task;
    private final WorkflowExecutionStartedEventAttributes startedEvent;
    private HistoryEvent first;

    private ReplayDecisionTaskWithHistoryIterator(
        WorkflowExecution execution, Iterator<HistoryEvent> history) {
      this.history = history;
      first = history.next();
      this.startedEvent = first.getWorkflowExecutionStartedEventAttributes();
      if (startedEvent == null) {
        throw new IllegalArgumentException(
            "First history event is not WorkflowExecutionStarted, but: " + first.getEventType());
      }
      task = new PollForDecisionTaskResponse();
      task.setWorkflowExecution(execution);
      task.setStartedEventId(Long.MAX_VALUE);
      task.setPreviousStartedEventId(Long.MAX_VALUE);
      task.setWorkflowType(startedEvent.getWorkflowType());
    }

    @Override
    public PollForDecisionTaskResponse getDecisionTask() {
      return task;
    }

    @Override
    public Iterator<HistoryEvent> getHistory() {
      return new Iterator<HistoryEvent>() {
        @Override
        public boolean hasNext() {
          return first != null || history.hasNext();
        }

        @Override
        public HistoryEvent next() {
          if (first != null) {
            HistoryEvent result = first;
            first = null;
            return result;
          }
          return history.next();
        }
      };
    }

    @Override
    public WorkflowExecutionStartedEventAttributes getStartedEvent() {
      return startedEvent;
    }
  }
}
