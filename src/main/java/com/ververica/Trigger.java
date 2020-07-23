package com.ververica;

import java.util.Optional;

public class Trigger {

	private final Type type;
	private final String id;

	private final String command;

	public Trigger(Type type, String id, String command) {
		this.type = type;
		this.id = id;
		this.command = command;
	}

	public Type getType() {
		return type;
	}

	public String getId() {
		return id;
	}

	public Optional<String> getCommand() {
		return Optional.ofNullable(command);
	}

	public enum Type {
		PUSH,
		MANUAL
	}
}
