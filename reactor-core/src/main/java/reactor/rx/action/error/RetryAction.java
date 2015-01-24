/*
 * Copyright (c) 2011-2014 Pivotal Software, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package reactor.rx.action.error;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.Environment;
import reactor.core.Dispatcher;
import reactor.core.dispatch.SynchronousDispatcher;
import reactor.core.dispatch.TailRecurseDispatcher;
import reactor.fn.Consumer;
import reactor.fn.Predicate;
import reactor.rx.action.Action;
import reactor.rx.subscription.PushSubscription;

/**
 * @author Stephane Maldini
 * @since 2.0
 */
public class RetryAction<T> extends Action<T, T> {

	private final long                   numRetries;
	private final Predicate<Throwable>   retryMatcher;
	private final Publisher<? extends T> rootPublisher;
	private final Consumer<Throwable> throwableConsumer = new ThrowableConsumer();
	private       long                currentNumRetries = 0;
	private Dispatcher dispatcher;

	public RetryAction(Dispatcher dispatcher, int numRetries,
	                   Predicate<Throwable> predicate, Publisher<? extends T> parentStream) {
		this.numRetries = numRetries;
		this.retryMatcher = predicate;
		this.rootPublisher = parentStream;

		if (SynchronousDispatcher.INSTANCE == dispatcher) {
			this.dispatcher = Environment.tailRecurse();
		} else {
			this.dispatcher = dispatcher;
		}
	}

	@Override
	protected void doSubscribe(Subscription subscription) {
		dispatcher = Environment.tailRecurse();
		super.doSubscribe(subscription);
	}

	@Override
	protected void doNext(T ev) {
		currentNumRetries = 0;
		broadcastNext(ev);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void onError(Throwable throwable) {
		if ((numRetries != -1 && ++currentNumRetries > numRetries) && (retryMatcher == null || !retryMatcher.test
				(throwable))) {
			doError(throwable);
			currentNumRetries = 0;
		} else {
			dispatcher.dispatch(throwable, throwableConsumer, null);

		}
	}

	@Override
	public final Dispatcher getDispatcher() {
		return dispatcher;
	}

	private class ThrowableConsumer implements Consumer<Throwable> {
		@Override
		public void accept(Throwable throwable) {
			PushSubscription<?> upstream = upstreamSubscription;
			if (upstream != null) {
				long pendingRequests = upstream.pendingRequestSignals();
				if (rootPublisher != null) {
					if(TailRecurseDispatcher.class.isAssignableFrom(dispatcher.getClass())){
						dispatcher.shutdown();
						dispatcher = Environment.tailRecurse();
					}
					cancel();
					rootPublisher.subscribe(RetryAction.this);
					upstream = upstreamSubscription;
				}
				if (upstream != null && pendingRequests >= 0) {
					upstream.request(pendingRequests != Long.MAX_VALUE ? pendingRequests + 1 : pendingRequests);
				}
			}
		}
	}
}
