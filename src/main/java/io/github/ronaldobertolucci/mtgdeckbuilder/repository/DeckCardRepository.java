package io.github.ronaldobertolucci.mtgdeckbuilder.repository;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeckCardRepository extends JpaRepository<DeckCard, UUID> {
}
