package com.flippingutilities;

public enum RecipeSortOption {
	INPUT_COUNT("Input Count"),
	OUTPUT_COUNT("Output Count"),
	NAME("Name");

	private final String displayName;

	RecipeSortOption(String displayName) {
		this.displayName = displayName;
	}

	@Override
	public String toString() {
		return displayName;
	}
}
