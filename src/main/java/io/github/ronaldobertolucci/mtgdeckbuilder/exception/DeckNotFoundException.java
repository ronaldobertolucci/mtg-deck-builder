package io.github.ronaldobertolucci.mtgdeckbuilder.exception;
public class DeckNotFoundException extends RuntimeException {
    public DeckNotFoundException() { super("Deck not found"); }
}
