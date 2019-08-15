package com.ververica;

public class Trigger {

	private final Type type;
	private final String id;

	public Trigger(Type type, String id) {
		this.type = type;
		this.id = id;
	}

	public Type getType() {
		return type;
	}

	public String getId() {
		return id;
	}

	public enum Type {
		PUSH,
		MANUAL
	}
}
