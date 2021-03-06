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

package com.uber.cadence.internal.replay;

import com.uber.cadence.ActivityTaskCancelRequestedEventAttributes;
import com.uber.cadence.ActivityTaskCanceledEventAttributes;
import com.uber.cadence.ActivityTaskCompletedEventAttributes;
import com.uber.cadence.ActivityTaskFailedEventAttributes;
import com.uber.cadence.ActivityTaskScheduledEventAttributes;
import com.uber.cadence.ActivityTaskTimedOutEventAttributes;
import com.uber.cadence.CancelTimerFailedEventAttributes;
import com.uber.cadence.CancelWorkflowExecutionDecisionAttributes;
import com.uber.cadence.ChildWorkflowExecutionStartedEventAttributes;
import com.uber.cadence.CompleteWorkflowExecutionDecisionAttributes;
import com.uber.cadence.ContinueAsNewWorkflowExecutionDecisionAttributes;
import com.uber.cadence.Decision;
import com.uber.cadence.DecisionTaskCompletedEventAttributes;
import com.uber.cadence.DecisionType;
import com.uber.cadence.FailWorkflowExecutionDecisionAttributes;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.RequestCancelActivityTaskFailedEventAttributes;
import com.uber.cadence.RequestCancelExternalWorkflowExecutionDecisionAttributes;
import com.uber.cadence.RequestCancelExternalWorkflowExecutionFailedEventAttributes;
import com.uber.cadence.RequestCancelExternalWorkflowExecutionInitiatedEventAttributes;
import com.uber.cadence.ScheduleActivityTaskDecisionAttributes;
import com.uber.cadence.SignalExternalWorkflowExecutionDecisionAttributes;
import com.uber.cadence.SignalExternalWorkflowExecutionInitiatedEventAttributes;
import com.uber.cadence.StartChildWorkflowExecutionDecisionAttributes;
import com.uber.cadence.StartChildWorkflowExecutionFailedEventAttributes;
import com.uber.cadence.StartChildWorkflowExecutionInitiatedEventAttributes;
import com.uber.cadence.StartTimerDecisionAttributes;
import com.uber.cadence.TaskList;
import com.uber.cadence.TimerCanceledEventAttributes;
import com.uber.cadence.TimerStartedEventAttributes;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.worker.WorkflowExecutionException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class DecisionsHelper {

  //    private static final Logger log = LoggerFactory.getLogger(DecisionsHelper.class);

  /**
   * TODO: Update constant once Cadence introduces the limit of decision per completion. Or remove
   * code path if Cadence deals with this problem differently like paginating through decisions.
   */
  static final int MAXIMUM_DECISIONS_PER_COMPLETION = 10000;

  static final String FORCE_IMMEDIATE_DECISION_TIMER = "FORCE_IMMEDIATE_DECISION";

  private final PollForDecisionTaskResponse task;

  private long idCounter;

  private final Map<Long, String> activitySchedulingEventIdToActivityId = new HashMap<>();

  private final Map<Long, String> signalInitiatedEventIdToSignalId = new HashMap<>();

  /** Use access-order to ensure that decisions are emitted in order of their creation */
  private final Map<DecisionId, DecisionStateMachine> decisions =
      new LinkedHashMap<>(100, 0.75f, true);

  private byte[] workflowContextData;

  private byte[] workfowContextFromLastDecisionCompletion;

  DecisionsHelper(PollForDecisionTaskResponse task) {
    this.task = task;
  }

  void scheduleActivityTask(ScheduleActivityTaskDecisionAttributes schedule) {
    DecisionId decisionId = new DecisionId(DecisionTarget.ACTIVITY, schedule.getActivityId());
    addDecision(decisionId, new ActivityDecisionStateMachine(decisionId, schedule));
  }

  /**
   * @return true if cancellation already happened as schedule event was found in the new decisions
   *     list
   */
  boolean requestCancelActivityTask(String activityId, Runnable immediateCancellationCallback) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, activityId));
    decision.cancel(immediateCancellationCallback);
    return decision.isDone();
  }

  boolean handleActivityTaskClosed(String activityId) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, activityId));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleActivityTaskScheduled(HistoryEvent event) {
    ActivityTaskScheduledEventAttributes attributes =
        event.getActivityTaskScheduledEventAttributes();
    String activityId = attributes.getActivityId();
    activitySchedulingEventIdToActivityId.put(event.getEventId(), activityId);
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, activityId));
    decision.handleInitiatedEvent(event);
    return decision.isDone();
  }

  boolean handleActivityTaskCancelRequested(HistoryEvent event) {
    ActivityTaskCancelRequestedEventAttributes attributes =
        event.getActivityTaskCancelRequestedEventAttributes();
    String activityId = attributes.getActivityId();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, activityId));
    decision.handleCancellationInitiatedEvent();
    return decision.isDone();
  }

  public boolean handleActivityTaskCanceled(HistoryEvent event) {
    ActivityTaskCanceledEventAttributes attributes = event.getActivityTaskCanceledEventAttributes();
    String activityId = getActivityId(attributes);
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, activityId));
    decision.handleCancellationEvent();
    return decision.isDone();
  }

  boolean handleRequestCancelActivityTaskFailed(HistoryEvent event) {
    RequestCancelActivityTaskFailedEventAttributes attributes =
        event.getRequestCancelActivityTaskFailedEventAttributes();
    String activityId = attributes.getActivityId();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, activityId));
    decision.handleCancellationFailureEvent(event);
    return decision.isDone();
  }

  void startChildWorkflowExecution(StartChildWorkflowExecutionDecisionAttributes schedule) {
    DecisionId decisionId =
        new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, schedule.getWorkflowId());
    addDecision(decisionId, new ChildWorkflowDecisionStateMachine(decisionId, schedule));
  }

  void handleStartChildWorkflowExecutionInitiated(HistoryEvent event) {
    StartChildWorkflowExecutionInitiatedEventAttributes attributes =
        event.getStartChildWorkflowExecutionInitiatedEventAttributes();
    String workflowId = attributes.getWorkflowId();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, workflowId));
    decision.handleInitiatedEvent(event);
  }

  public boolean handleStartChildWorkflowExecutionFailed(HistoryEvent event) {
    StartChildWorkflowExecutionFailedEventAttributes attributes =
        event.getStartChildWorkflowExecutionFailedEventAttributes();
    String workflowId = attributes.getWorkflowId();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, workflowId));
    decision.handleInitiationFailedEvent(event);
    return decision.isDone();
  }

  /**
   * @return true if cancellation already happened as schedule event was found in the new decisions
   *     list
   */
  boolean requestCancelExternalWorkflowExecution(
      boolean childWorkflow,
      RequestCancelExternalWorkflowExecutionDecisionAttributes request,
      Runnable immediateCancellationCallback) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, request.getWorkflowId()));
    decision.cancel(immediateCancellationCallback);
    return decision.isDone();
  }

  void handleRequestCancelExternalWorkflowExecutionInitiated(HistoryEvent event) {
    RequestCancelExternalWorkflowExecutionInitiatedEventAttributes attributes =
        event.getRequestCancelExternalWorkflowExecutionInitiatedEventAttributes();
    String workflowId = attributes.getWorkflowExecution().getWorkflowId();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, workflowId));
    decision.handleCancellationInitiatedEvent();
  }

  void handleRequestCancelExternalWorkflowExecutionFailed(HistoryEvent event) {
    RequestCancelExternalWorkflowExecutionFailedEventAttributes attributes =
        event.getRequestCancelExternalWorkflowExecutionFailedEventAttributes();
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(
                DecisionTarget.EXTERNAL_WORKFLOW,
                attributes.getWorkflowExecution().getWorkflowId()));
    decision.handleCancellationFailureEvent(event);
  }

  void signalExternalWorkflowExecution(SignalExternalWorkflowExecutionDecisionAttributes signal) {
    DecisionId decisionId =
        new DecisionId(
            DecisionTarget.SIGNAL, new String(signal.getControl(), StandardCharsets.UTF_8));
    addDecision(decisionId, new SignalDecisionStateMachine(decisionId, signal));
  }

  void cancelSignalExternalWorkflowExecution(
      String signalId, Runnable immediateCancellationCallback) {
    DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.SIGNAL, signalId));
    decision.cancel(immediateCancellationCallback);
  }

  void handleSignalExternalWorkflowExecutionInitiated(HistoryEvent event) {
    SignalExternalWorkflowExecutionInitiatedEventAttributes attributes =
        event.getSignalExternalWorkflowExecutionInitiatedEventAttributes();
    String signalId = new String(attributes.getControl(), StandardCharsets.UTF_8);
    signalInitiatedEventIdToSignalId.put(event.getEventId(), signalId);
    DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.SIGNAL, signalId));
    decision.handleInitiatedEvent(event);
  }

  public boolean handleSignalExternalWorkflowExecutionFailed(String signalId) {
    DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.SIGNAL, signalId));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  public boolean handleExternalWorkflowExecutionSignaled(String signalId) {
    DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.SIGNAL, signalId));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  void startTimer(StartTimerDecisionAttributes request, Object createTimerUserContext) {
    String timerId = request.getTimerId();
    DecisionId decisionId = new DecisionId(DecisionTarget.TIMER, timerId);
    addDecision(decisionId, new TimerDecisionStateMachine(decisionId, request));
  }

  boolean cancelTimer(String timerId, Runnable immediateCancellationCallback) {
    DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.TIMER, timerId));
    if (decision.isDone()) {
      // Cancellation callbacks are not deregistered and might be invoked after timer firing
      return true;
    }
    decision.cancel(immediateCancellationCallback);
    return decision.isDone();
  }

  public void handleChildWorkflowExecutionStarted(HistoryEvent event) {
    ChildWorkflowExecutionStartedEventAttributes attributes =
        event.getChildWorkflowExecutionStartedEventAttributes();
    String workflowId = attributes.getWorkflowExecution().getWorkflowId();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, workflowId));
    decision.handleStartedEvent(event);
  }

  boolean handleChildWorkflowExecutionClosed(String workflowId) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, workflowId));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  public void handleChildWorkflowExecutionCancelRequested(HistoryEvent event) {}

  public boolean handleChildWorkflowExecutionCanceled(String workflowId) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.EXTERNAL_WORKFLOW, workflowId));
    decision.handleCancellationEvent();
    return decision.isDone();
  }

  boolean handleTimerClosed(String timerId) {
    DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.TIMER, timerId));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleTimerStarted(HistoryEvent event) {
    TimerStartedEventAttributes attributes = event.getTimerStartedEventAttributes();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.TIMER, attributes.getTimerId()));
    decision.handleInitiatedEvent(event);
    return decision.isDone();
  }

  boolean handleTimerCanceled(HistoryEvent event) {
    TimerCanceledEventAttributes attributes = event.getTimerCanceledEventAttributes();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.TIMER, attributes.getTimerId()));
    decision.handleCancellationEvent();
    return decision.isDone();
  }

  boolean handleCancelTimerFailed(HistoryEvent event) {
    CancelTimerFailedEventAttributes attributes = event.getCancelTimerFailedEventAttributes();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.TIMER, attributes.getTimerId()));
    decision.handleCancellationFailureEvent(event);
    return decision.isDone();
  }

  void completeWorkflowExecution(byte[] output) {
    Decision decision = new Decision();
    CompleteWorkflowExecutionDecisionAttributes complete =
        new CompleteWorkflowExecutionDecisionAttributes();
    complete.setResult(output);
    decision.setCompleteWorkflowExecutionDecisionAttributes(complete);
    decision.setDecisionType(DecisionType.CompleteWorkflowExecution);
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, null);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  void continueAsNewWorkflowExecution(ContinueAsNewWorkflowExecutionParameters continueParameters) {
    WorkflowExecutionStartedEventAttributes startedEvent =
        task.getHistory().getEvents().get(0).getWorkflowExecutionStartedEventAttributes();
    ContinueAsNewWorkflowExecutionDecisionAttributes attributes =
        new ContinueAsNewWorkflowExecutionDecisionAttributes();
    attributes.setInput(continueParameters.getInput());
    String workflowType = continueParameters.getWorkflowType();
    if (workflowType != null && !workflowType.isEmpty()) {
      attributes.setWorkflowType(new WorkflowType().setName(workflowType));
    } else {
      attributes.setWorkflowType(task.getWorkflowType());
    }
    int executionStartToClose = continueParameters.getExecutionStartToCloseTimeoutSeconds();
    if (executionStartToClose == 0) {
      executionStartToClose = startedEvent.getExecutionStartToCloseTimeoutSeconds();
    }
    attributes.setExecutionStartToCloseTimeoutSeconds(executionStartToClose);
    int taskStartToClose = continueParameters.getTaskStartToCloseTimeoutSeconds();
    if (taskStartToClose == 0) {
      taskStartToClose = startedEvent.getTaskStartToCloseTimeoutSeconds();
    }
    attributes.setTaskStartToCloseTimeoutSeconds(taskStartToClose);
    String taskList = continueParameters.getTaskList();
    if (taskList == null || taskList.isEmpty()) {
      taskList = startedEvent.getTaskList().getName();
    }
    TaskList tl = new TaskList();
    tl.setName(taskList);
    attributes.setTaskList(tl);
    Decision decision = new Decision();
    decision.setDecisionType(DecisionType.ContinueAsNewWorkflowExecution);
    decision.setContinueAsNewWorkflowExecutionDecisionAttributes(attributes);

    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, null);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  void failWorkflowExecution(WorkflowExecutionException failure) {
    Decision decision = new Decision();
    FailWorkflowExecutionDecisionAttributes failAttributes =
        new FailWorkflowExecutionDecisionAttributes();
    failAttributes.setReason(failure.getReason());
    failAttributes.setDetails(failure.getDetails());
    decision.setFailWorkflowExecutionDecisionAttributes(failAttributes);
    decision.setDecisionType(DecisionType.FailWorkflowExecution);
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, null);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  /**
   * @return <code>false</code> means that cancel failed, <code>true</code> that
   *     CancelWorkflowExecution was created.
   */
  void cancelWorkflowExecution() {
    Decision decision = new Decision();
    CancelWorkflowExecutionDecisionAttributes cancel =
        new CancelWorkflowExecutionDecisionAttributes();
    cancel.setDetails((byte[]) null);
    decision.setCancelWorkflowExecutionDecisionAttributes(cancel);
    decision.setDecisionType(DecisionType.CancelWorkflowExecution);
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, null);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  List<Decision> getDecisions() {
    List<Decision> result = new ArrayList<>(MAXIMUM_DECISIONS_PER_COMPLETION + 1);
    for (DecisionStateMachine decisionStateMachine : decisions.values()) {
      Decision decision = decisionStateMachine.getDecision();
      if (decision != null) {
        result.add(decision);
      }
    }
    // Include FORCE_IMMEDIATE_DECISION timer only if there are more then 100 events
    int size = result.size();
    if (size > MAXIMUM_DECISIONS_PER_COMPLETION
        && !isCompletionEvent(result.get(MAXIMUM_DECISIONS_PER_COMPLETION - 2))) {
      result = result.subList(0, MAXIMUM_DECISIONS_PER_COMPLETION - 1);
      StartTimerDecisionAttributes attributes = new StartTimerDecisionAttributes();
      attributes.setStartToFireTimeoutSeconds(0);
      attributes.setTimerId(FORCE_IMMEDIATE_DECISION_TIMER);
      Decision d = new Decision();
      d.setStartTimerDecisionAttributes(attributes);
      d.setDecisionType(DecisionType.StartTimer);
      result.add(d);
    }

    return result;
  }

  private boolean isCompletionEvent(Decision decision) {
    DecisionType type = decision.getDecisionType();
    switch (type) {
      case CancelWorkflowExecution:
      case CompleteWorkflowExecution:
      case FailWorkflowExecution:
      case ContinueAsNewWorkflowExecution:
        return true;
      default:
        return false;
    }
  }

  public void handleDecisionTaskStartedEvent() {
    int count = 0;
    Iterator<DecisionStateMachine> iterator = decisions.values().iterator();
    DecisionStateMachine next = null;

    DecisionStateMachine decisionStateMachine = getNextDecision(iterator);
    while (decisionStateMachine != null) {
      next = getNextDecision(iterator);
      if (++count == MAXIMUM_DECISIONS_PER_COMPLETION
          && next != null
          && !isCompletionEvent(next.getDecision())) {
        break;
      }
      decisionStateMachine.handleDecisionTaskStartedEvent();
      decisionStateMachine = next;
    }
    if (next != null && count < MAXIMUM_DECISIONS_PER_COMPLETION) {
      next.handleDecisionTaskStartedEvent();
    }
  }

  private DecisionStateMachine getNextDecision(Iterator<DecisionStateMachine> iterator) {
    DecisionStateMachine result = null;
    while (result == null && iterator.hasNext()) {
      result = iterator.next();
      if (result.getDecision() == null) {
        result = null;
      }
    }
    return result;
  }

  @Override
  public String toString() {
    return WorkflowExecutionUtils.prettyPrintDecisions(getDecisions());
  }

  void setWorkflowContextData(byte[] workflowState) {
    this.workflowContextData = workflowState;
  }

  /** @return new workflow state or null if it didn't change since the last decision completion */
  byte[] getWorkflowContextDataToReturn() {
    if (workfowContextFromLastDecisionCompletion == null
        || !Arrays.equals(workfowContextFromLastDecisionCompletion, workflowContextData)) {
      return workflowContextData;
    }
    return null;
  }

  void handleDecisionCompletion(
      DecisionTaskCompletedEventAttributes decisionTaskCompletedEventAttributes) {
    workfowContextFromLastDecisionCompletion =
        decisionTaskCompletedEventAttributes.getExecutionContext();
  }

  PollForDecisionTaskResponse getTask() {
    return task;
  }

  String getActivityId(ActivityTaskCanceledEventAttributes attributes) {
    Long sourceId = attributes.getScheduledEventId();
    return activitySchedulingEventIdToActivityId.get(sourceId);
  }

  String getActivityId(ActivityTaskCompletedEventAttributes attributes) {
    Long sourceId = attributes.getScheduledEventId();
    return activitySchedulingEventIdToActivityId.get(sourceId);
  }

  String getActivityId(ActivityTaskFailedEventAttributes attributes) {
    Long sourceId = attributes.getScheduledEventId();
    return activitySchedulingEventIdToActivityId.get(sourceId);
  }

  String getActivityId(ActivityTaskTimedOutEventAttributes attributes) {
    Long sourceId = attributes.getScheduledEventId();
    return activitySchedulingEventIdToActivityId.get(sourceId);
  }

  String getSignalIdFromExternalWorkflowExecutionSignaled(long initiatedEventId) {
    return signalInitiatedEventIdToSignalId.get(initiatedEventId);
  }

  void addDecision(DecisionId decisionId, DecisionStateMachine decision) {
    decisions.put(decisionId, decision);
  }

  private DecisionStateMachine getDecision(DecisionId decisionId) {
    DecisionStateMachine result = decisions.get(decisionId);
    if (result == null) {
      throw new IllegalArgumentException(
          "Unknown "
              + decisionId
              + ". The possible causes are a nondeterministic workflow definition code or an incompatible change in the workflow definition."
              + "See the \"Workflow Implementation Constraints\" section from the github.com/uber-java/cadence-client README");
    }
    return result;
  }

  public String getNextId() {
    return String.valueOf(++idCounter);
  }
}
