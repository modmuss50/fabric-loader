/*
 * Copyright 2016 FabricMC
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

package net.fabricmc.loader.impl.util;

import java.util.Arrays;
import java.util.Map;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.util.Expression.DynamicFunction;
import net.fabricmc.loader.impl.util.Expression.ExpressionEvaluateException;

public final class ExpressionFunctions {
	public static void registerLate(Map<String, DynamicFunction> out) {
		out.put("mod", new StringToBooleanFunction() {
			@Override
			boolean evaluate(String name) {
				return FabricLoaderImpl.INSTANCE.getModInternal(name) != null;
			}
		});
	}

	public static void checkString1(Object[] args) throws ExpressionEvaluateException {
		if (args.length != 1 || !(args[0] instanceof String)) throw new ExpressionEvaluateException("not a single string argument: "+Arrays.toString(args));
	}

	private abstract static class StringToBooleanFunction implements DynamicFunction {
		@Override
		public Object evaluate(Object... args) throws ExpressionEvaluateException {
			checkString1(args);

			return evaluate(args);
		}

		abstract boolean evaluate(String arg);
	}
}
