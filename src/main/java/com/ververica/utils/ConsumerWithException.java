/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.utils;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface ConsumerWithException<T, E extends Throwable> {
	void accept(T t) throws E;

	static <T, E extends Exception> Consumer<T> wrap(ConsumerWithException<T, E> consumerWithException, BiConsumer<T, Exception> errorHandler) {
		return t -> {
			try {
				consumerWithException.accept(t);
			} catch (Exception e) {
				errorHandler.accept(t, e);
			}
		};
	}
}
