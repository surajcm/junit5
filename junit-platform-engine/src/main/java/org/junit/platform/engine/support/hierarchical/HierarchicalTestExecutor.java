/*
 * Copyright 2015-2018 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * http://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.platform.engine.support.hierarchical;

import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.support.hierarchical.Node.SkipResult;

/**
 * Implementation core of all {@link TestEngine TestEngines} that wish to
 * use the {@link Node} abstraction as the driving principle for structuring
 * and executing test suites.
 *
 * <p>A {@code HierarchicalTestExecutor} is instantiated by a concrete
 * implementation of {@link HierarchicalTestEngine} and takes care of
 * executing nodes in the hierarchy in the appropriate order as well as
 * firing the necessary events in the {@link EngineExecutionListener}.
 *
 * @param <C> the type of {@code EngineExecutionContext} used by the
 * {@code HierarchicalTestEngine}
 * @since 1.0
 */
class HierarchicalTestExecutor<C extends EngineExecutionContext> {

	private final TestDescriptor rootTestDescriptor;
	private final EngineExecutionListener listener;
	private final C rootContext;

	HierarchicalTestExecutor(ExecutionRequest request, C rootContext) {
		this.rootTestDescriptor = request.getRootTestDescriptor();
		this.listener = request.getEngineExecutionListener();
		this.rootContext = rootContext;
	}

	void execute() {
		new NodeExecutor(this.rootTestDescriptor).execute(this.rootContext, new ExecutionTracker());
	}

	class NodeExecutor {

		private final TestDescriptor testDescriptor;
		private final Node<C> node;
		private final ThrowableCollector throwableCollector = new ThrowableCollector();
		private C context;
		private SkipResult skipResult;
		private boolean started;

		NodeExecutor(TestDescriptor testDescriptor) {
			this.testDescriptor = testDescriptor;
			node = asNode(testDescriptor);
		}

		void execute(C parentContext, ExecutionTracker tracker) {
			tracker.markExecuted(testDescriptor);
			throwableCollector.execute(() -> context = node.prepare(parentContext));
			if (throwableCollector.isEmpty()) {
				throwableCollector.execute(() -> skipResult = node.shouldBeSkipped(context));
			}
			if (throwableCollector.isEmpty() && !skipResult.isSkipped()) {
				executeRecursively(tracker);
			}
			if (context != null) {
				throwableCollector.execute(() -> node.cleanUp(context));
			}
			reportDone();
		}

		private void executeRecursively(ExecutionTracker tracker) {
			listener.executionStarted(testDescriptor);
			started = true;

			throwableCollector.execute(() -> {
				context = node.before(context);

				context = node.execute(context, dynamicTestDescriptor -> {
					listener.dynamicTestRegistered(dynamicTestDescriptor);
					new NodeExecutor(dynamicTestDescriptor).execute(context, tracker);
				});

				// @formatter:off
				testDescriptor.getChildren().stream()
						.filter(child -> !tracker.wasAlreadyExecuted(child))
						.forEach(child -> new NodeExecutor(child).execute(context, tracker));
				// @formatter:on
			});
			throwableCollector.execute(() -> node.after(context));
		}

		private void reportDone() {
			if (throwableCollector.isEmpty() && skipResult.isSkipped()) {
				listener.executionSkipped(testDescriptor, skipResult.getReason().orElse("<unknown>"));
				return;
			}
			if (!started) {
				// Call executionStarted first to comply with the contract of EngineExecutionListener.
				listener.executionStarted(testDescriptor);
			}
			listener.executionFinished(testDescriptor, throwableCollector.toTestExecutionResult());
		}
	}

	@SuppressWarnings("unchecked")
	private Node<C> asNode(TestDescriptor testDescriptor) {
		return (testDescriptor instanceof Node ? (Node<C>) testDescriptor : noOpNode);
	}

	@SuppressWarnings("rawtypes")
	private static final Node noOpNode = new Node() {
	};

}
