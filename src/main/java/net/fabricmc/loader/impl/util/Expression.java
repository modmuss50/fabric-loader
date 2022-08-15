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

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;

public final class Expression {
	public static void main(String[] args) {
		String[] test = {
				"mod(reqMod) && !mod(exceptionMod)",
				"func1(a, b, c) && func2()",
				"12.34 > 2",
				"12.34 < 2",
				"1.234 < 2 && 1 < 2 && 3 < 4",
				"12.34 < 2 && 1 < 2 && 3 < 4",
				"1.234 < 2 && 6 < 2 && 3 < 4",
				"1.234 < 2 && 1 < 2 && hello(ic2, test) && 3 < 4 || bla() > 6",
				"\"asd\" == asd",
				"-5 < 10",
				"-(5) < 10",
				"!(-(5) < 10)",
				"a == a",
				"-asd(12, 34)",
				"-stringfunc(asd, \"yx\")",
				"asd()",
				"as:d345_()",
				"a() && b() || c()",
				"a() && (b() || c())",
				"a() < b()",
				"!(a() < b())",
				"a() && !(b() || c())",
				"a() && !(!b() || !c())",
				"!(a() && !(!b() || !c()))",
				"!!a()",
				"--a()",
				"!(a(1,2) && !(!b(3,4) || !c(5.6)))"
		};

		for (String s : test) {
			System.out.println(s);

			try {
				Expression res = parse(s);
				System.out.println(res);
				System.out.println(res.toInfixString());
				System.out.println("maybeBool: "+res.maybeBooleanExpression()+" hasResult: "+res.hasResult());
			} catch (ExpressionParseException e) {
				throw new RuntimeException(e);
			}

			System.out.println();
		}
	}

	public static Expression parse(String str) throws ExpressionParseException {
		// shunting yard algorithm with inline tokenization
		Deque<Object> out = new ArrayDeque<>();
		Deque<Object> opStack = new ArrayDeque<>();
		boolean lastIsOperand = false;

		for (int i = 0; i < str.length(); i++) {
			char c = str.charAt(i);
			Operator op = null;

			switch (c) {
			case '<':
				op = i + 1 < str.length() && str.charAt(i + 1) == '=' ? Operator.LESS_EQUAL : Operator.LESS; // <= or <
				break;
			case '>':
				op = i + 1 < str.length() && str.charAt(i + 1) == '=' ? Operator.GREATER_EQUAL : Operator.GREATER; // >= or >
				break;
			case '&':
			case '|':
				if (i + 1 < str.length() && str.charAt(i + 1) == c) {
					op = c == '&' ? Operator.AND : Operator.OR; // && or ||
				}

				break;
			case '!':
				op = i + 1 < str.length() && str.charAt(i + 1) == '=' ? Operator.NOT_EQUAL : Operator.NOT; // != or !
				break;
			case '=':
				if (i + 1 < str.length() && str.charAt(i + 1) == c) op = Operator.EQUAL; // ==
				break;
			case '-':
				op = Operator.UNARY_MINUS; // -
				break;
			}

			if (op != null) { // operator
				if (!lastIsOperand) { // unary operator
					if (op.binary) {
						throw new ExpressionParseException("binary operator "+op+" without preceding operand", i);
					}

					opStack.addLast(op);
				} else {
					Object top;

					while ((top = opStack.peekLast()) != null
							&& top != Character.valueOf('(')
							&& (!isUnknown(top) || Operator.getPrecedence(top) <= op.precedence)) {
						out.addLast(top);
						opStack.removeLast();
					}

					opStack.addLast(op);
				}

				i += op.serialized.length() - 1;
				lastIsOperand = false;
			} else if (c == '(') {
				if (opStack.peekLast() instanceof String) {
					String name = (String) opStack.removeLast();
					if (!isValidFunctionName(name)) throw new ExpressionParseException("invalid function name "+name, i - name.length());
					opStack.addLast(new Function(name));
				}

				opStack.addLast(c);
				lastIsOperand = false;
			} else if (c == ')') {
				int count = 0;
				Object top;

				while ((top = opStack.removeLast()) != Character.valueOf('(')) {
					out.addLast(top);
					count++;
				}

				if (opStack.peekLast() instanceof Function) {
					Function function = (Function) opStack.removeLast();
					function.setArgCount(count);
					out.addLast(function);
				}

				lastIsOperand = true;
			} else if (c == ',') {
				lastIsOperand = false;
			} else if (isDigit(c)) {
				boolean foundDot = false;
				int end;

				for (end = i + 1; end < str.length(); end++) {
					c = str.charAt(end);

					if (!foundDot && c == '.') {
						foundDot = true;
					} else if (!isDigit(c)) {
						break;
					}
				}

				String part = str.substring(i, end);
				boolean negative = opStack.peekLast() == Operator.UNARY_MINUS;
				if (negative) opStack.removeLast();

				if (foundDot) {
					double value = Double.parseDouble(part);
					if (negative) value = -value;
					opStack.addLast(value);
				} else {
					long value = Long.parseLong(part);
					if (negative) value = -value;
					opStack.addLast(value);
				}

				i = end - 1;
				lastIsOperand = true;
			} else if (c == '"') {
				int end = str.indexOf('"', i + 1);
				if (end < 0) throw new ExpressionParseException("unterminated quoted string", i);

				opStack.addLast(str.substring(i + 1, end));
				i = end;
				lastIsOperand = true;
			} else if (isLetter(c)) {
				int end;

				for (end = i + 1; end < str.length(); end++) {
					c = str.charAt(end);
					if (!isValidPlainStringChar(c)) break; // may use : and digits as long as they are not at the beginning
				}

				opStack.addLast(str.substring(i, end)); // treat the string as a function for now (operator stack only, no Function() wrapping), actual decision happens at next operator or (
				i = end - 1;
				lastIsOperand = true;
			} else if (!Character.isWhitespace(c)) {
				throw new RuntimeException("unexpected character "+c+" at offset "+i);
			}
		}

		Object top;

		while ((top = opStack.pollLast()) != null) {
			if (top == Character.valueOf('(')) throw new ExpressionParseException("mismatched parenthesis");
			out.addLast(top);
		}

		int sum = 0;

		for (Iterator<Object> it = out.descendingIterator(); it.hasNext(); ) {
			Object obj = it.next();
			sum += 1 - getArgCount(obj);
		}

		if (sum != 1) throw new ExpressionParseException("expression didn't yield exactly one result but "+sum);

		System.out.println(out);

		// partial evaluation to optimize the expression

		try {
			out = partialEvaluate(out, null);
		} catch (ExpressionEvaluateException e) {
			throw new ExpressionParseException("partial evaluation failed", e);
		}

		return new Expression(out);
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isLetter(char c) {
		return c >= 'a' && c <= 'z';
	}

	private static boolean isValidPlainStringChar(char c) {
		return isLetter(c) || isDigit(c) || ":.-_".indexOf(c) >= 0;
	}

	private static boolean isValidPlainString(String str) {
		if (str.length() == 0 || !isLetter(str.charAt(0))) return false;

		for (int i = 1; i < str.length(); i++) {
			if (!isValidPlainStringChar(str.charAt(i))) return false;
		}

		return true;
	}

	private static boolean isValidFunctionName(String name) {
		int max = name.length() - 1;

		for (int i = 0; i <= max; i++) {
			char c = name.charAt(i);

			if (!isLetter(c)
					&& (i == 0 || !isDigit(c) && c != '_')
					&& (i == 0 || i == max || c != ':')) {
				return false;
			}
		}

		return true;
	}

	private final Deque<Object> tokens;

	private Expression(Deque<Object> tokens) {
		this.tokens = tokens;
	}

	public Expression partialEvaluate(Map<String, DynamicFunction> functions) throws ExpressionEvaluateException {
		if (tokens.size() == 1 && !isUnknown(tokens.getFirst())) return this;

		Deque<Object> res = partialEvaluate(tokens, functions);

		return res != tokens ? new Expression(res) : this;
	}

	public Object evaluate(Map<String, DynamicFunction> functions) throws ExpressionEvaluateException {
		Deque<Object> res = partialEvaluate(tokens, functions);

		return hasResult(res) ? res.getFirst() : null;
	}

	public boolean evaluateBoolean(Map<String, DynamicFunction> functions) throws ExpressionEvaluateException {
		Object res = evaluate(functions);
		if (!(res instanceof Boolean)) throw new ExpressionEvaluateException(hasResult() ? "non-boolean value" : "unresolved functions");

		return (boolean) res;
	}

	public boolean maybeBooleanExpression() {
		return !tokens.isEmpty() && OperandType.BOOLEAN.matches(tokens.getLast());
	}

	public boolean hasResult() {
		return hasResult(tokens);
	}

	public Object getResult() {
		return hasResult(tokens) ? tokens.getFirst() : null;
	}

	private static boolean hasResult(Deque<Object> tokens) {
		return tokens.size() == 1 && !isUnknown(tokens.getFirst());
	}

	public String toInfixString() {
		final class Node {
			String val;
			int precedence;

			Node(String val, int precedence) {
				this.val = val;
				this.precedence = precedence;
			}

			@Override
			public String toString() {
				return val;
			}
		}

		Deque<Object> stack = new ArrayDeque<>();
		StringBuilder sb = new StringBuilder();

		for (Object token : tokens) {
			if (token instanceof Function) {
				Function function = (Function) token;
				sb.append(function.name);
				sb.append('(');

				for (int i = 0; i < function.argCount; i++) {
					if (i > 0) sb.append(", ");

					Object arg = stack.removeLast();
					sb.append(arg);
				}

				sb.append(')');
				stack.add(sb.toString());
				sb.setLength(0);
			} else if (token instanceof Operator) {
				Operator op = (Operator) token;
				Object argA = stack.removeLast();
				boolean needParensA = argA instanceof Node && ((Node) argA).precedence > op.precedence;

				if (op.binary) {
					Object argB = stack.removeLast();

					boolean needParensB = argB instanceof Node && ((Node) argB).precedence > op.precedence;
					if (needParensB) sb.append('(');
					sb.append(argB);
					if (needParensB) sb.append(')');
					sb.append(' ');
					sb.append(op);
					sb.append(' ');
					if (needParensA) sb.append('(');
					sb.append(argA);
					if (needParensA) sb.append(')');
				} else {
					sb.append(op);
					if (needParensA) sb.append('(');
					sb.append(argA);
					if (needParensA) sb.append(')');
				}

				stack.add(new Node(sb.toString(), op.precedence));
				sb.setLength(0);
			} else if (token instanceof String && !isValidPlainString((String) token)) {
				stack.add(String.format("\"%s\"", token));
			} else {
				stack.add(token);
			}
		}

		return stack.getFirst().toString();
	}

	@Override
	public String toString() {
		return tokens.toString();
	}

	private static Deque<Object> partialEvaluate(Deque<Object> tokens, Map<String, DynamicFunction> functions) throws ExpressionEvaluateException {
		Deque<Object> result = new ArrayDeque<>(tokens.size());
		boolean changed = false;
		Deque<Object> tmp = new ArrayDeque<>(tokens.size());

		for (Object token : tokens) {
			if (token instanceof Function) {
				if (partialEvaluateFunction((Function) token, result, functions)) {
					changed = true;
					continue;
				}
			} else if (token instanceof Operator) {
				if (partialEvaluateOperator((Operator) token, result, tmp)) {
					changed = true;
					continue;
				}
			}

			result.add(token);
		}

		return changed ? result : tokens;
	}

	/**
	 * Try to replace a function call with its result.
	 */
	private static boolean partialEvaluateFunction(Function function, Deque<Object> stack, Map<String, DynamicFunction> functions) throws ExpressionEvaluateException {
		DynamicFunction resolvedFunction = functions != null ? functions.get(function.name) : null;
		if (resolvedFunction == null) return false;

		Object[] args = new Object[function.argCount];

		for (int i = 0; i < args.length; i++) {
			Object arg = args[i] = stack.removeLast();

			if (isUnknown(arg)) {
				for (int j = i; j >= 0; j--) {
					stack.add(args[j]);
				}

				return false;
			}
		}

		try {
			Object res = evaluateFunction(resolvedFunction, args);

			if (res == null) {
				for (int i = args.length - 1; i >= 0; i--) {
					stack.add(args[i]);
				}

				return false;
			} else {
				stack.add(res);

				return true;
			}
		} catch (Throwable t) {
			String resName = "";

			try {
				resName = String.format(" (%s)", resolvedFunction);
			} catch (Throwable t2) {
				// ignore
			}

			throw new ExpressionEvaluateException("Error evaluating function "+function.name+resName+" with "+Arrays.toString(args), t);
		}
	}

	private static Object evaluateFunction(DynamicFunction function, Object[] args) throws ExpressionEvaluateException {
		Object result = function.evaluate(args);

		if (result == null) {
			return null;
		} else if (result instanceof Boolean
				|| result instanceof String
				|| result instanceof Long
				|| result instanceof Double) {
			return result;
		} else if (result instanceof Number) {
			Number number = (Number) result;

			if (number instanceof Integer
					|| number instanceof Byte
					|| number instanceof Short) {
				return number.longValue();
			} else {
				return number.doubleValue();
			}
		} else {
			throw new RuntimeException("unsupported return type: "+result.getClass().getName());
		}
	}

	/**
	 * Try to replace an operator and its operands with its result.
	 */
	private static boolean partialEvaluateOperator(Operator op, Deque<Object> stack, Deque<Object> tmp) {
		Object argA = stack.removeLast();
		Object argB;

		if (!op.binary) { // unary operand
			argB = null;
		} else { // binary operand
			// seek over inputs for argA by removing into tmp
			int req = getArgCount(argA);

			while (req-- > 0) {
				Object val = stack.removeLast();
				tmp.addFirst(val);
				req += getArgCount(val);
			}

			argB = stack.removeLast();
		}

		//if (op.binary) System.out.printf("%s ", argB);
		//System.out.printf("%s %s%n", argA, op);

		if (!op.checkArgTypes(argB, argA)) {
			if (op.binary) {
				throw new RuntimeException("Invalid operand types for operator "+op.serialized+": "+argB+", "+argA);
			} else {
				throw new RuntimeException("Invalid operand type for operator "+op.serialized+": "+argA);
			}
		}

		boolean unknownA = isUnknown(argA);
		boolean unknownB = op.binary && isUnknown(argB);

		if ((unknownA || unknownB) && (!op.canEvalUnknown || unknownA && unknownB)) { // can't evaluate if both inputs are unknown or the operator doesn't support unknown inputs
			if (op.binary) {
				stack.add(argB);
				stack.addAll(tmp);
				tmp.clear();
			}

			if (op == Operator.NOT && argA instanceof Operator) { // optimize ! operator for unknown input
				Operator subOp = (Operator) argA;

				if (subOp == Operator.NOT) {
					//System.out.println("  cancelled");
					return true;
				} else if (subOp.opposite != null) {
					if (subOp == Operator.AND || subOp == Operator.OR) { // non-trivial opposite needing de morgan's law
						// temporarily remove first sub-operand and its inputs to get to the second sub-operand
						Object subArgA = stack.removeLast();
						int req = getArgCount(subArgA);

						while (req-- > 0) {
							Object val = stack.removeLast();
							tmp.addFirst(val);
							req += getArgCount(val);
						}

						// insert negation for second sub-operand if it can't be optimized away
						if (!partialEvaluateOperator(Operator.NOT, stack, new ArrayDeque<>())) {
							stack.add(Operator.NOT);
						}

						// re-add first sub-operand with its inputs
						stack.addAll(tmp);
						tmp.clear();
						stack.add(subArgA);

						// insert negation for first sub-operand if it can't be optimized away
						if (!partialEvaluateOperator(Operator.NOT, stack, tmp)) {
							stack.add(Operator.NOT);
						}
					}

					stack.add(subOp.opposite);
					//System.out.println("  opposite");
					return true;
				}
			} else if (op == Operator.UNARY_MINUS && argA == Operator.UNARY_MINUS) { // optimize duplicate - operator for unknown input
				return true;
			}

			stack.add(argA);
			//System.out.println("  same");
			return false;
		} else { // can evaluate, yielding a constant result or one of the operands
			Object res = op.evaluate(argB, argA);

			if (res != argB && unknownB) { // argB was consumed, remove its inputs
				int req = getArgCount(argA);

				while (req-- > 0) {
					Object val = stack.removeLast();
					req += getArgCount(val);
				}
			}

			if (res == argA && unknownA) { // argA is the result, restore it fully (argB doesn't need restoring because it'd have never been removed from the stack if it was the result)
				stack.addAll(tmp);
			}

			stack.addLast(res);
			//System.out.println("  = "+res);
			tmp.clear();

			return true;
		}
	}

	private static boolean isUnknown(Object obj) {
		return obj instanceof Function || obj instanceof Operator;
	}

	private static int getArgCount(Object obj) {
		if (obj instanceof Operator) {
			return ((Operator) obj).binary ? 2 : 1;
		} else if (obj instanceof Function) {
			return ((Function) obj).argCount;
		} else {
			return 0;
		}
	}

	@SuppressWarnings("serial")
	public static final class ExpressionParseException extends Exception {
		private final int offset;

		ExpressionParseException(String message) {
			super(message);

			this.offset = -1;
		}

		ExpressionParseException(String message, Throwable cause) {
			super(message, cause);

			this.offset = -1;
		}

		ExpressionParseException(String message, int offset) {
			super(message+" at offset "+offset);

			this.offset = offset;
		}

		public int getOffset() {
			return offset;
		}
	}

	@SuppressWarnings("serial")
	public static final class ExpressionEvaluateException extends Exception {
		public ExpressionEvaluateException(String message) {
			super(message);
		}

		public ExpressionEvaluateException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	public interface DynamicFunction {
		/**
		 * Evaluate for the supplied arguments.
		 *
		 * @return result or null if evaluation is not yet possible
		 */
		Object evaluate(Object... args) throws ExpressionEvaluateException;
	}

	private enum Operator {
		NOT("!", false, OperandType.BOOLEAN, OperandType.BOOLEAN, false, 2) {
			@Override
			Object evaluate(Object argA, Object argB) {
				return !(boolean) argB;
			}
		},
		UNARY_MINUS("-", false, OperandType.NUMBER, OperandType.NUMBER, false, 2) {
			@Override
			Object evaluate(Object argA, Object argB) {
				if (argB instanceof Long) {
					return -(long) argB;
				} else {
					return -(double) argB;
				}
			}
		},
		GREATER_EQUAL(">=", true, OperandType.NUMBER, OperandType.BOOLEAN, false, 3) {
			@Override
			Object evaluate(Object argA, Object argB) {
				return compareNumbers(argA, argB) >= 0;
			}
		},
		LESS_EQUAL("<=", true, OperandType.NUMBER, OperandType.BOOLEAN, false, 3) {
			@Override
			Object evaluate(Object argA, Object argB) {
				return compareNumbers(argA, argB) <= 0;
			}
		},
		GREATER(">", true, OperandType.NUMBER, OperandType.BOOLEAN, false, 3) {
			@Override
			Object evaluate(Object argA, Object argB) {
				return compareNumbers(argA, argB) > 0;
			}
		},
		LESS("<", true, OperandType.NUMBER, OperandType.BOOLEAN, false, 3) {
			@Override
			Object evaluate(Object argA, Object argB) {
				return compareNumbers(argA, argB) < 0;
			}
		},
		EQUAL("==", true, null, OperandType.BOOLEAN, false, 4) {
			@Override
			Object evaluate(Object argA, Object argB) {
				if (argA instanceof Boolean && argB instanceof Boolean) {
					return (boolean) argA == (boolean) argB;
				} else if ((argA instanceof Long || argA instanceof Double)
						&& (argB instanceof Long || argB instanceof Double)) {
					return compareNumbers(argA, argB) == 0;
				} else if (argA instanceof String && argB instanceof String) {
					return argA.equals(argB);
				} else {
					return false;
				}
			}
		},
		NOT_EQUAL("!=", true, null, OperandType.BOOLEAN, false, 4) {
			@Override
			Object evaluate(Object argA, Object argB) {
				if (argA instanceof Boolean && argB instanceof Boolean) {
					return (boolean) argA == (boolean) argB;
				} else if ((argA instanceof Long || argA instanceof Double)
						&& (argB instanceof Long || argB instanceof Double)) {
					return compareNumbers(argA, argB) != 0;
				} else if (argA instanceof String && argB instanceof String) {
					return !argA.equals(argB);
				} else {
					return false;
				}
			}
		},
		AND("&&", true, OperandType.BOOLEAN, OperandType.BOOLEAN, true, 5) {
			@Override
			Object evaluate(Object argA, Object argB) {
				if (argA instanceof Boolean) {
					boolean a = (boolean) argA;

					if (argB instanceof Boolean) {
						return a && (boolean) argB;
					} else if (a) {
						return argB;
					}
				} else if ((boolean) argB) {
					return argA;
				}

				return false;
			}
		},
		OR("||", true, OperandType.BOOLEAN, OperandType.BOOLEAN, true, 6) {
			@Override
			Object evaluate(Object argA, Object argB) {
				if (argA instanceof Boolean) {
					boolean a = (boolean) argA;

					if (argB instanceof Boolean) {
						return a || (boolean) argB;
					} else if (!a) {
						return argB;
					}
				} else if (!(boolean) argB) {
					return argA;
				}

				return true;
			}
		};

		static final int FUNCTION_PRECEDENCE = 1;

		final String serialized;
		final boolean binary;
		final OperandType operandType;
		final OperandType resultType;
		final boolean canEvalUnknown;
		final int precedence;
		Operator opposite;

		static {
			GREATER_EQUAL.opposite = LESS;
			LESS_EQUAL.opposite = GREATER;
			GREATER.opposite = LESS_EQUAL;
			LESS.opposite = GREATER_EQUAL;
			EQUAL.opposite = NOT_EQUAL;
			NOT_EQUAL.opposite = EQUAL;
			AND.opposite = OR;
			OR.opposite = AND;
		}

		Operator(String serialized, boolean binary, OperandType operandType, OperandType resultType, boolean canEvalUnknown, int precedence) {
			this.serialized = serialized;
			this.binary = binary;
			this.operandType = operandType;
			this.resultType = resultType;
			this.canEvalUnknown = canEvalUnknown;
			this.precedence = precedence;
		}

		/**
		 * Check if the argument types may be suitable for the operator.
		 *
		 * <p>For unary operators only {@code argB} is used.
		 */
		boolean checkArgTypes(Object argA, Object argB) {
			return operandType == null || operandType.matches(argB) && (!binary || operandType.matches(argA));
		}

		/**
		 * Evaluate for the supplied args.
		 *
		 * <p>For unary operators only {@code argB} is used.
		 *
		 * <p>The arguments need to pass {@link #checkArgTypes}.
		 */
		abstract Object evaluate(Object argA, Object argB);

		@Override
		public String toString() {
			return serialized;
		}

		static int getPrecedence(Object obj) {
			if (obj instanceof Operator) {
				return ((Operator) obj).precedence;
			} else if (obj instanceof Function) {
				return FUNCTION_PRECEDENCE;
			} else {
				throw new IllegalArgumentException("no operator/function: "+obj);
			}
		}

		private static int compareNumbers(Object argA, Object argB) {
			if (argA instanceof Long) {
				long a = (long) argA;

				if (argB instanceof Long) {
					return Long.compare(a, (long) argB);
				} else {
					return Double.compare(a, (double) argB);
				}
			} else {
				double a = (double) argA;

				if (argB instanceof Long) {
					return Double.compare(a, (long) argB);
				} else {
					return Double.compare(a, (double) argB);
				}
			}
		}
	}

	private enum OperandType {
		BOOLEAN {
			@Override
			boolean matches(Object obj) {
				return obj instanceof Boolean
						|| obj instanceof Function
						|| obj instanceof Operator && ((Operator) obj).resultType == this;
			}
		},
		NUMBER {
			@Override
			boolean matches(Object obj) {
				return obj instanceof Long
						|| obj instanceof Double
						|| obj instanceof Function
						|| obj instanceof Operator && ((Operator) obj).resultType == this;
			}
		},
		STRING {
			@Override
			boolean matches(Object obj) {
				return obj instanceof String
						|| obj instanceof Function
						|| obj instanceof Operator && ((Operator) obj).resultType == this;
			}
		};

		abstract boolean matches(Object obj);
	}

	private static final class Function {
		private final String name;
		private int argCount;

		Function(String name) {
			this.name = name;
		}

		void setArgCount(int argCount) {
			this.argCount = argCount;
		}

		@Override
		public String toString() {
			return name+"("+argCount+")";
		}
	}
}
