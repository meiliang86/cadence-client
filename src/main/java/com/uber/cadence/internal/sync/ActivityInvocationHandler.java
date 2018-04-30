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

import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.common.MethodRetry;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.internal.sync.AsyncInternal.AsyncMarker;
import com.uber.cadence.workflow.ActivityStub;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowInterceptor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Dynamic implementation of a strongly typed child workflow interface. */
class ActivityInvocationHandler implements InvocationHandler {

  private final ActivityOptions options;
  private final WorkflowInterceptor activityExecutor;
  private final Map<Method, Function<Object[], Object>> methodFunctions = new HashMap<>();

  static InvocationHandler newInstance(
      ActivityOptions options, WorkflowInterceptor activityExecutor) {
    return new ActivityInvocationHandler(options, activityExecutor);
  }

  @SuppressWarnings("unchecked")
  static <T> T newProxy(Class<T> activityInterface, InvocationHandler invocationHandler) {
    return (T)
        Proxy.newProxyInstance(
            WorkflowInternal.class.getClassLoader(),
            new Class<?>[] {activityInterface, AsyncMarker.class},
            invocationHandler);
  }

  private ActivityInvocationHandler(ActivityOptions options, WorkflowInterceptor activityExecutor) {
    this.options = options;
    this.activityExecutor = activityExecutor;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    Function<Object[], Object> function = methodFunctions.get(method);
    if (function == null) {
      try {
        if (method.equals(Object.class.getMethod("toString"))) {
          // TODO: activity info
          return "ActivityInvocationHandler";
        }
        if (!method.getDeclaringClass().isInterface()) {
          throw new IllegalArgumentException(
              "Interface type is expected: " + method.getDeclaringClass());
        }
        MethodRetry methodRetry = method.getAnnotation(MethodRetry.class);
        ActivityMethod activityMethod = method.getAnnotation(ActivityMethod.class);
        String activityName;
        if (activityMethod == null || activityMethod.name().isEmpty()) {
          activityName = InternalUtils.getSimpleName(method);
        } else {
          activityName = activityMethod.name();
        }

        ActivityOptions mergedOptions = ActivityOptions.merge(activityMethod, methodRetry, options);
        ActivityStub stub = ActivityStubImpl.newInstance(mergedOptions, activityExecutor);
        function = (a) -> stub.execute(activityName, method.getReturnType(), a);
        methodFunctions.put(method, function);
      } catch (NoSuchMethodException e) {
        throw Workflow.wrap(e);
      }
    }
    return function.apply(args);
  }
}
