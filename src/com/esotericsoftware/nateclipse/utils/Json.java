
package com.esotericsoftware.nateclipse.utils;

import java.io.Writer;

/** Builder API for emitting JSON to a {@link Writer}.
 * @author Nathan Sweet */
public class Json {
	static private final int isObject = 0b01, needsComma = 0b10;

	private final StringBuilder buffer;
	private int stack, depth;
	private boolean named;

	public Json () {
		this(64);
	}

	public Json (int initialBufferSize) {
		buffer = new StringBuilder(initialBufferSize);
	}

	public StringBuilder getBuffer () {
		return buffer;
	}

	private void requireCommaOrName () {
		if (depth == 0) return; // top level, first value
		if ((stack & isObject) != 0) {
			if (!named) throw new IllegalStateException("Name must be set.");
			named = false;
		} else {
			if ((stack & needsComma) != 0)
				buffer.append(',');
			else
				stack |= needsComma;
		}
	}

	public Json object () {
		requireCommaOrName();
		buffer.append('{');
		push(true);
		return this;
	}

	public Json array () {
		requireCommaOrName();
		buffer.append('[');
		push(false);
		return this;
	}

	public Json value (String value) {
		requireCommaOrName();
		appendValue(value);
		return this;
	}

	public Json value (boolean value) {
		requireCommaOrName();
		buffer.append(value);
		return this;
	}

	public Json value (int value) {
		requireCommaOrName();
		buffer.append(value);
		return this;
	}

	public Json value (long value) {
		requireCommaOrName();
		buffer.append(value);
		return this;
	}

	public Json value (float value) {
		requireCommaOrName();
		buffer.append(value);
		return this;
	}

	public Json value (double value) {
		requireCommaOrName();
		buffer.append(value);
		return this;
	}

	/** Writes the specified JSON string, without quoting or escaping. */
	public Json json (String json) {
		requireCommaOrName();
		buffer.append(json);
		return this;
	}

	public Json name (String name) {
		nameValue(name);
		named = true;
		return this;
	}

	private void nameValue (String name) {
		if ((stack & isObject) == 0) throw new IllegalStateException("Current item must be an object.");
		if ((stack & needsComma) != 0)
			buffer.append(',');
		else
			stack |= needsComma;
		appendQuoted(name);
		buffer.append(':');
	}

	public Json object (String name) {
		nameValue(name);
		buffer.append('{');
		push(true);
		return this;
	}

	public Json array (String name) {
		nameValue(name);
		buffer.append('[');
		push(false);
		return this;
	}

	public Json set (String name, String value) {
		nameValue(name);
		appendValue(value);
		return this;
	}

	public Json set (String name, boolean value) {
		nameValue(name);
		buffer.append(value);
		return this;
	}

	public Json set (String name, int value) {
		nameValue(name);
		buffer.append(value);
		return this;
	}

	public Json set (String name, long value) {
		nameValue(name);
		buffer.append(value);
		return this;
	}

	public Json set (String name, float value) {
		nameValue(name);
		buffer.append(value);
		return this;
	}

	public Json set (String name, double value) {
		nameValue(name);
		buffer.append(value);
		return this;
	}

	/** Writes the specified JSON string, without quoting or escaping. */
	public Json json (String name, String json) {
		nameValue(name);
		buffer.append(json);
		return this;
	}

	private void push (boolean object) {
		if (depth == 32) throw new IllegalStateException("Nesting too deep.");
		stack = (stack << 2) | (object ? isObject : 0);
		depth++;
	}

	public Json pop () {
		if (depth == 0) throw new IllegalStateException("No object or array to pop.");
		if (named) throw new IllegalStateException("Expected an object, array, or value since a name was set.");
		buffer.append((stack & isObject) != 0 ? '}' : ']');
		stack >>>= 2;
		depth--;
		if (depth > 0) stack |= needsComma;
		return this;
	}

	public Json close () {
		while (depth > 0)
			pop();
		return this;
	}

	public void reset () {
		buffer.setLength(0);
		stack = 0;
		depth = 0;
		named = false;
	}

	public String toString () {
		return buffer.toString();
	}

	private void appendValue (Object value) {
		if (value == null)
			buffer.append("null");
		else if (value instanceof Number || value instanceof Boolean)
			buffer.append(value);
		else
			appendQuoted(value.toString());
	}

	private void appendQuoted (String value) {
		int length = value.length();
		int i = 0;
		while (true) {
			if (i == length) {
				buffer.append('"');
				buffer.append(value);
				buffer.append('"');
				return;
			}
			char c = value.charAt(i);
			if (c == '"' || c == '\\' || c < 0x20) break;
			i++;
		}

		buffer.ensureCapacity(length + 8);
		buffer.append('"').append(value, 0, i);
		for (; i < length; i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"' -> buffer.append("\\\"");
			case '\\' -> buffer.append("\\\\");
			case '\n' -> buffer.append("\\n");
			case '\r' -> buffer.append("\\r");
			case '\t' -> buffer.append("\\t");
			default -> {
				if (c < 0x20) {
					buffer.append("\\u00");
					buffer.append(Character.forDigit((c >> 4) & 0xf, 16));
					buffer.append(Character.forDigit(c & 0xf, 16));
				} else
					buffer.append(c);
			}
			}
		}
		buffer.append('"');
	}

	static public String quote (String value) {
		int length = value.length();
		int i = 0;
		while (true) {
			if (i == length) return '"' + value + '"';
			char c = value.charAt(i);
			if (c == '"' || c == '\\' || c < 0x20) break;
			i++;
		}

		StringBuilder buffer = new StringBuilder(length + 8);
		buffer.append('"').append(value, 0, i);
		for (; i < length; i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"' -> buffer.append("\\\"");
			case '\\' -> buffer.append("\\\\");
			case '\n' -> buffer.append("\\n");
			case '\r' -> buffer.append("\\r");
			case '\t' -> buffer.append("\\t");
			default -> {
				if (c < 0x20) {
					buffer.append("\\u00");
					buffer.append(Character.forDigit((c >> 4) & 0xf, 16));
					buffer.append(Character.forDigit(c & 0xf, 16));
				} else
					buffer.append(c);
			}
			}
		}
		return buffer.append('"').toString();
	}
}
